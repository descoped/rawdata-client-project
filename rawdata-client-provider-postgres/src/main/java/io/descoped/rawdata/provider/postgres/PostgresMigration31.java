package io.descoped.rawdata.provider.postgres;

import io.descoped.rawdata.api.RawdataException;
import io.descoped.rawdata.api.RawdataMetadataClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves the topics 3.1 wrote, in the shared tables {@code rawdata_message}, {@code rawdata_content} and
 * {@code rawdata_metadata} of the schema, into tables of their own, and keeps a client from starting on a schema whose
 * 3.1 data is not moved yet, where its topics would read as empty and a 3.1 writer would go unseen.
 * <ol>
 *     <li>Checks that nothing stops the move: no metadata key over {@value RawdataMetadataClient#MAX_KEY_BYTES} bytes
 *     (3.1 took longer ones) and no 3.1 topic already in the registry. Otherwise it refuses, changing nothing.</li>
 *     <li>Fences, in one transaction: renames the tables to {@code rawdata_v31_*} and records every topic in
 *     {@code rawdata_v31_migration}. A 3.1 writer still running fails from then on. A restarted one creates its tables
 *     again, and a client of this version then refuses to start, so what it writes is never lost unseen.</li>
 *     <li>Moves each topic in a transaction of its own, under the topic's creation lock: registers it, creates its
 *     tables and copies its rows inside PostgreSQL, ids, order and content unchanged; then marks it done. A move that
 *     stopped resumes with the topics not done.</li>
 * </ol>
 * It runs only when the client is started with {@value PostgresRawdataClientInitializer#MIGRATE_31}{@code =true}.
 * Nothing is dropped: the renamed tables stay until an administrator drops them.
 */
final class PostgresMigration31 {

    private static final System.Logger LOG = System.getLogger(PostgresMigration31.class.getName());

    /**
     * The key of the schema lock taken to fence, next to the registry's 0.
     */
    private static final int FENCE_KEY = 1;
    private static final String[] KINDS = {"message", "content", "metadata"};
    private static final int LISTED = 20;
    private static final String PROGRESS = "rawdata_v31_migration";

    private final Jdbc jdbc;
    private final PostgresSchema schema;
    private final PostgresTopics topics;

    PostgresMigration31(Jdbc jdbc, PostgresSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.topics = new PostgresTopics(jdbc, schema);
    }

    private String old(String kind) {
        return schema.table(oldName(kind));
    }

    private static String oldName(String kind) {
        return "rawdata_" + kind;
    }

    private String fenced(String kind) {
        return schema.table("rawdata_v31_" + kind);
    }

    private String progress() {
        return schema.table(PROGRESS);
    }

    /**
     * Migrates if asked to, and refuses a start on a schema with 3.1 data not moved.
     *
     * @param migrate whether the client was started to migrate
     * @throws RawdataException if the schema holds 3.1 data not moved and the client is not migrating, or the move
     *                          cannot be done
     */
    void run(boolean migrate) {
        boolean unfenced = exists(oldName("message")) || exists(oldName("content")) || exists(oldName("metadata"));
        boolean started = exists(PROGRESS);
        if (unfenced && started) {
            throw new RawdataException("PostgreSQL schema '" + schema.name() + "' has 3.1 tables again ("
                    + old("message") + "), next to the ones already moved to " + fenced("message")
                    + ": a 3.1 client wrote after the migration began. Stop it, move or drop what it wrote, and start again");
        }
        if (unfenced) {
            if (!migrate) {
                throw new RawdataException("PostgreSQL schema '" + schema.name() + "' holds topics written by 3.1 ("
                        + old("message") + "), which this version does not read; stop every 3.1 client and start one"
                        + " client with " + PostgresRawdataClientInitializer.MIGRATE_31 + "=true to move them");
            }
            fence();
        }
        if (!exists(PROGRESS)) {
            return;
        }
        List<String> pending = pending();
        if (pending.isEmpty()) {
            return;
        }
        if (!migrate) {
            throw new RawdataException("PostgreSQL schema '" + schema.name() + "' has a migration from 3.1 not finished ("
                    + pending.size() + " topics left in " + progress() + "); start one client with "
                    + PostgresRawdataClientInitializer.MIGRATE_31 + "=true to finish it");
        }
        int moved = 0;
        for (String topic : pending) {
            if (move(topic)) {
                moved++;
            }
        }
        LOG.log(System.Logger.Level.INFO, "Moved " + moved + " topics written by 3.1 in schema '" + schema.name()
                + "' to tables of their own; the old tables are kept as " + fenced("*"));
    }

    private boolean exists(String table) {
        return jdbc.transaction("look for " + table, connection -> tableExists(connection, table));
    }

    private void fence() {
        jdbc.transaction("fence the 3.1 tables of schema " + schema.name(), connection -> {
            PostgresSchema.lock(connection, FENCE_KEY);
            if (!tableExists(connection, oldName("message"))) {
                return null; // another client fenced while this one waited
            }
            for (String kind : KINDS) {
                if (!tableExists(connection, oldName(kind))) {
                    throw new RawdataException("PostgreSQL schema '" + schema.name() + "' has " + old("message")
                            + " but not " + old(kind) + ": not the tables of a 3.1 client, nothing is moved");
                }
            }
            refuseLongKeys(connection);
            refuseRegistered(connection);
            try (Statement statement = connection.createStatement()) {
                for (String kind : KINDS) {
                    statement.addBatch("ALTER TABLE " + old(kind) + " RENAME TO rawdata_v31_" + kind);
                }
                statement.addBatch("CREATE TABLE " + progress() + " (topic varchar(100) COLLATE \"C\" PRIMARY KEY, "
                        + "done boolean NOT NULL DEFAULT false)");
                statement.addBatch("INSERT INTO " + progress() + " (topic) SELECT topic FROM " + fenced("message")
                        + " UNION SELECT topic FROM " + fenced("metadata"));
                statement.executeBatch();
            }
            LOG.log(System.Logger.Level.INFO, "Fenced the 3.1 tables of schema '" + schema.name() + "' as "
                    + fenced("*") + "; moving their topics");
            return null;
        });
    }

    /**
     * Whether the schema has a table, read from the catalog with the statement's snapshot: after waiting for the fence
     * lock it sees another client's renames, which {@code to_regclass}'s cached lookup may not.
     */
    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?)")) {
            select.setString(1, schema.name());
            select.setString(2, table);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private void refuseLongKeys(Connection connection) throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT topic, left(name, 40) FROM " + old("metadata")
                + " WHERE octet_length(name) > ? ORDER BY topic, name LIMIT ?")) {
            select.setInt(1, RawdataMetadataClient.MAX_KEY_BYTES);
            select.setInt(2, LISTED + 1);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getString(1) + ": " + rows.getString(2) + "…");
                }
            }
        }
        if (!found.isEmpty()) {
            throw new RawdataException("PostgreSQL schema '" + schema.name() + "' has metadata keys over "
                    + RawdataMetadataClient.MAX_KEY_BYTES + " bytes in " + old("metadata") + ", which this version does"
                    + " not read; nothing is moved. Rename or remove them first: " + listed(found));
        }
    }

    private void refuseRegistered(Connection connection) throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT r.topic FROM " + schema.table("rawdata_topic")
                + " r WHERE EXISTS (SELECT 1 FROM " + old("message") + " m WHERE m.topic = r.topic)"
                + " OR EXISTS (SELECT 1 FROM " + old("metadata") + " d WHERE d.topic = r.topic) ORDER BY r.topic LIMIT ?")) {
            select.setInt(1, LISTED + 1);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getString(1));
                }
            }
        }
        if (!found.isEmpty()) {
            throw new RawdataException("PostgreSQL schema '" + schema.name() + "' has topics written both by 3.1 and by"
                    + " this version; nothing is moved. Copy them by hand: " + listed(found));
        }
    }

    private static String listed(List<String> found) {
        return String.join(", ", found.subList(0, Math.min(LISTED, found.size()))) + (found.size() > LISTED ? ", …" : "");
    }

    private List<String> pending() {
        return jdbc.transaction("list the topics left to move from 3.1", connection -> {
            List<String> pending = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT topic FROM " + progress()
                    + " WHERE NOT done ORDER BY topic");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    pending.add(rows.getString(1));
                }
            }
            return pending;
        });
    }

    /**
     * Moves one topic, unless another client moved it while this one waited.
     *
     * @return whether this client moved it
     */
    private boolean move(String topic) {
        return jdbc.transaction("move topic " + topic + " from 3.1", connection -> {
            PostgresSchema.lock(connection, schema.name() + "." + topic);
            try (PreparedStatement done = connection.prepareStatement("SELECT done FROM " + progress() + " WHERE topic = ?")) {
                done.setString(1, topic);
                try (ResultSet rows = done.executeQuery()) {
                    if (!rows.next() || rows.getBoolean(1)) {
                        return false;
                    }
                }
            }
            if (topics.registered(connection, topic)) { // the move of a topic commits with its registration
                throw new RawdataException("PostgreSQL topic " + topic + " in schema '" + schema.name() + "' was written"
                        + " by this version before its 3.1 data was moved; copy it by hand from " + fenced("*"));
            }
            PostgresTopics.Topic created = topics.createIn(connection, topic);
            String message = schema.table("rawdata_" + created.n() + "_message");
            String content = schema.table("rawdata_" + created.n() + "_content");
            String metadata = schema.table("rawdata_" + created.n() + "_metadata");
            copy(connection, topic, "INSERT INTO " + message + " (id, position, ordering_group, sequence_number)"
                    + " SELECT id, position, ordering_group, sequence_number FROM " + fenced("message") + " WHERE topic = ?");
            copy(connection, topic, "INSERT INTO " + content + " (id, ordinal, name, data)"
                    + " SELECT id, ordinal, name, data FROM " + fenced("content") + " WHERE topic = ?");
            copy(connection, topic, "INSERT INTO " + metadata + " (name, data)"
                    + " SELECT name, data FROM " + fenced("metadata") + " WHERE topic = ?");
            copy(connection, topic, "UPDATE " + progress() + " SET done = true WHERE topic = ?");
            return true;
        });
    }

    private static void copy(Connection connection, String topic, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, topic);
            statement.executeUpdate();
        }
    }
}
