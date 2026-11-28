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
import java.util.TreeSet;

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
 *     tables and copies its rows inside PostgreSQL, ids, order and content unchanged; then marks it done. A topic that
 *     cannot be moved is listed at the end, after the others are moved; a move that stopped resumes with the topics
 *     not done.</li>
 *     <li>Once every topic is done, renames {@code rawdata_v31_migration} to {@code rawdata_v31_migrated}, so later
 *     starts, readers' too, need nothing of it.</li>
 * </ol>
 * It runs only when the client is started with {@value PostgresRawdataClientInitializer#MIGRATE_31}{@code =true}.
 * Nothing is dropped: the renamed tables stay until an administrator drops them. Every check of which tables exist
 * reads {@code pg_class} in one statement, so it sees another client's fence whole or not at all ({@code to_regclass}'s
 * cached lookup may miss a rename after a lock wait).
 */
final class PostgresMigration31 {

    private static final System.Logger LOG = System.getLogger(PostgresMigration31.class.getName());

    private static final String MESSAGE = "rawdata_message";
    private static final String CONTENT = "rawdata_content";
    private static final String METADATA = "rawdata_metadata";
    private static final String FENCED_MESSAGE = "rawdata_v31_message";
    private static final String FENCED_CONTENT = "rawdata_v31_content";
    private static final String FENCED_METADATA = "rawdata_v31_metadata";
    private static final String FENCED = "rawdata_v31_*";
    private static final String PROGRESS = "rawdata_v31_migration";
    private static final String DONE = "rawdata_v31_migrated";
    private static final String TO_MIGRATE = "=true";
    private static final int LISTED = 20;
    private static final String ALTER_TABLE = "ALTER TABLE ";
    private static final String RENAME_TO = " RENAME TO ";

    /**
     * The tables of the schema that tell the state of a move, as one snapshot of the catalog sees them.
     */
    private record State(boolean old, boolean fenced, boolean progress, boolean done) {
    }

    private final Jdbc jdbc;
    private final PostgresSchema schema;
    private final PostgresTopics topics;
    private final String fenceLockKey;
    private final String state;
    private final String tableExists;
    private final List<String> fence;
    private final String longKeys;
    private final String registered;
    private final String pending;
    private final String claim;
    private final String markDone;
    private final String finish;

    PostgresMigration31(Jdbc jdbc, PostgresSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.topics = new PostgresTopics(jdbc, schema);
        this.fenceLockKey = schema.name() + "/rawdata_v31"; // a topic name has no '/', so no topic shares the key
        String progress = schema.table(PROGRESS);
        String oldMetadata = schema.table(METADATA);
        String inSchema = " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?";
        state = "SELECT bool_or(c.relname IN ('" + MESSAGE + "', '" + CONTENT + "', '" + METADATA + "')),"
                + " bool_or(c.relname IN ('" + FENCED_MESSAGE + "', '" + FENCED_CONTENT + "', '" + FENCED_METADATA + "')),"
                + " bool_or(c.relname = '" + PROGRESS + "'), bool_or(c.relname = '" + DONE + "')" + inSchema;
        tableExists = "SELECT count(*) = 1" + inSchema + " AND c.relname = ?";
        fence = List.of(
                ALTER_TABLE + schema.table(MESSAGE) + RENAME_TO + FENCED_MESSAGE,
                ALTER_TABLE + schema.table(CONTENT) + RENAME_TO + FENCED_CONTENT,
                ALTER_TABLE + oldMetadata + RENAME_TO + FENCED_METADATA,
                "CREATE TABLE " + progress + " (topic varchar(100) COLLATE \"C\" PRIMARY KEY, done boolean NOT NULL DEFAULT false)",
                "INSERT INTO " + progress + " (topic) SELECT topic FROM " + schema.table(FENCED_MESSAGE)
                        + " UNION SELECT topic FROM " + schema.table(FENCED_METADATA));
        longKeys = "SELECT topic, left(name, 40) FROM " + oldMetadata + " WHERE octet_length(name) > ? ORDER BY topic, name LIMIT ?";
        registered = "SELECT r.topic FROM " + schema.table("rawdata_topic") + " r"
                + " WHERE EXISTS (SELECT 1 FROM " + schema.table(MESSAGE) + " m WHERE m.topic = r.topic)"
                + " OR EXISTS (SELECT 1 FROM " + oldMetadata + " d WHERE d.topic = r.topic) ORDER BY r.topic LIMIT ?";
        pending = "SELECT topic FROM " + progress + " WHERE NOT done ORDER BY topic";
        claim = "SELECT done FROM " + progress + " WHERE topic = ?";
        markDone = "UPDATE " + progress + " SET done = true WHERE topic = ?";
        finish = ALTER_TABLE + progress + RENAME_TO + DONE;
    }

    /**
     * Migrates if asked to, and refuses a start on a schema with 3.1 data not moved.
     *
     * @param migrate whether the client was started to migrate
     * @throws RawdataException if the schema holds 3.1 data not moved and the client is not migrating, or the move
     *                          cannot be done
     */
    void run(boolean migrate) {
        State found = jdbc.transaction("look for tables of 3.1 in schema " + schema.name(), this::state);
        if (found.old() && (found.fenced() || found.progress() || found.done())) {
            throw again();
        }
        if (found.old()) {
            if (!migrate) {
                throw new RawdataException("PostgreSQL schema '" + schema.name() + "' holds topics written by 3.1 ("
                        + schema.table(MESSAGE) + "), which this version does not read; stop every 3.1 client and start"
                        + " one client with " + PostgresRawdataClientInitializer.MIGRATE_31 + TO_MIGRATE + " to move them");
            }
            fence();
        } else if (found.fenced() && !found.progress() && !found.done()) {
            throw new RawdataException("PostgreSQL schema '" + schema.name() + "' has 3.1's moved tables ("
                    + schema.table(FENCED) + ") but neither " + schema.table(PROGRESS) + " nor " + schema.table(DONE)
                    + ": whether every topic was moved cannot be told. Restore the one that was dropped from a backup, or drop "
                    + schema.table(FENCED)
                    + " if the move had finished");
        } else if (!found.progress()) {
            return; // nothing of 3.1, or a move that is done
        } else if (!migrate) {
            throw new RawdataException("PostgreSQL schema '" + schema.name() + "' has a migration from 3.1 not finished ("
                    + schema.table(PROGRESS) + "); start one client with " + PostgresRawdataClientInitializer.MIGRATE_31
                    + TO_MIGRATE + " to finish it");
        }
        moveAll();
    }

    private RawdataException again() {
        return new RawdataException("PostgreSQL schema '" + schema.name() + "' has 3.1 tables again ("
                + schema.table(MESSAGE) + "), next to the ones moved to " + schema.table(FENCED_MESSAGE)
                + ": a 3.1 client wrote after the migration began. Stop it, move or drop what it wrote, and start again");
    }

    private State state(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(state)) {
            select.setString(1, schema.name());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return new State(rows.getBoolean(1), rows.getBoolean(2), rows.getBoolean(3), rows.getBoolean(4));
            }
        }
    }

    private void fence() {
        jdbc.transaction("fence the 3.1 tables of schema " + schema.name(), connection -> {
            PostgresSchema.lock(connection, fenceLockKey);
            State now = state(connection);
            if (!now.old()) {
                return null; // another client fenced while this one waited
            }
            if (now.fenced() || now.progress() || now.done()) {
                throw again();
            }
            for (String table : List.of(MESSAGE, CONTENT, METADATA)) {
                if (!tableExists(connection, table)) {
                    throw new RawdataException("PostgreSQL schema '" + schema.name() + "' has some of 3.1's tables but"
                            + " not " + schema.table(table) + ": not the tables of a 3.1 client, nothing is moved");
                }
            }
            refuseLongKeys(connection);
            refuseRegistered(connection);
            try (Statement statement = connection.createStatement()) {
                for (String sql : fence) {
                    statement.addBatch(sql);
                }
                statement.executeBatch();
            }
            LOG.log(System.Logger.Level.INFO, "Fenced the 3.1 tables of schema '" + schema.name() + "' as "
                    + schema.table(FENCED) + "; moving their topics");
            return null;
        });
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(tableExists)) {
            select.setString(1, schema.name());
            select.setString(2, table);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private void refuseLongKeys(Connection connection) throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(longKeys)) {
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
                    + RawdataMetadataClient.MAX_KEY_BYTES + " bytes in " + schema.table(METADATA) + ", which this version"
                    + " does not read; nothing is moved. Rename or remove them first: " + listed(found));
        }
    }

    private void refuseRegistered(Connection connection) throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(registered)) {
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

    private List<String> pending(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(pending); ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    /**
     * Moves the topics not done, each in a transaction of its own, then finishes the move if none failed.
     */
    private void moveAll() {
        List<String> left = jdbc.transaction("list the topics left to move from 3.1", connection -> {
            PostgresSchema.lockShared(connection, fenceLockKey); // as a move, so finish() cannot rename under it
            return state(connection).progress() ? pending(connection) : List.<String>of();
        });
        int moved = 0;
        TreeSet<String> failed = new TreeSet<>();
        RawdataException first = null;
        for (String topic : left) {
            try {
                if (move(topic)) {
                    moved++;
                }
            } catch (RawdataException e) {
                failed.add(topic);
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (moved > 0) {
            LOG.log(System.Logger.Level.INFO, "Moved " + moved + " topics written by 3.1 in schema '" + schema.name()
                    + "' to tables of their own; the old tables are kept as " + schema.table(FENCED));
        }
        if (first != null) {
            throw new RawdataException("PostgreSQL could not move " + failed.size() + " topics written by 3.1 in schema '"
                    + schema.name() + "' (the others are moved): " + listed(List.copyOf(failed)) + ". Fix the cause and"
                    + " start again with " + PostgresRawdataClientInitializer.MIGRATE_31 + TO_MIGRATE + "; or copy a topic"
                    + " by hand from " + schema.table(FENCED) + " and then: UPDATE " + schema.table(PROGRESS)
                    + " SET done = true WHERE topic = '<topic>'", first);
        }
        finish();
    }

    /**
     * Moves one topic, unless another client moved it while this one waited.
     *
     * @return whether this client moved it
     */
    private boolean move(String topic) {
        return jdbc.transaction("move topic " + topic + " from 3.1", connection -> {
            PostgresSchema.lockShared(connection, fenceLockKey); // holds off finish(), which renames the progress table
            PostgresSchema.lock(connection, schema.name() + "." + topic);
            if (!state(connection).progress()) {
                return false; // another client moved every topic and finished
            }
            try (PreparedStatement done = connection.prepareStatement(claim)) {
                done.setString(1, topic);
                try (ResultSet rows = done.executeQuery()) {
                    if (!rows.next() || rows.getBoolean(1)) {
                        return false;
                    }
                }
            }
            if (topics.registered(connection, topic)) { // the move of a topic commits with its registration
                throw new RawdataException("PostgreSQL topic " + topic + " in schema '" + schema.name() + "' was written"
                        + " by this version before its 3.1 data was moved");
            }
            PostgresTopicSql sql = topics.createIn(connection, topic).sql();
            for (String copy : sql.from31) {
                execute(connection, copy, topic);
            }
            execute(connection, markDone, topic);
            return true;
        });
    }

    private static void execute(Connection connection, String sql, String topic) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, topic);
            statement.executeUpdate();
        }
    }

    /**
     * Renames the progress table once every topic is done, under the fence lock, once. Moves hold the same lock
     * shared, so none is under way while it renames.
     */
    private void finish() {
        jdbc.transaction("finish the migration from 3.1 of schema " + schema.name(), connection -> {
            PostgresSchema.lock(connection, fenceLockKey);
            if (!state(connection).progress() || !pending(connection).isEmpty()) {
                return null; // another client finished, or a topic is not moved yet
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(finish);
            }
            return null;
        });
    }
}
