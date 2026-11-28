package io.descoped.rawdata.provider.postgres;

import io.descoped.rawdata.api.RawdataException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The topics of a schema: finds a topic in the registry and creates it, with its tables, at its first write. A topic
 * found is remembered for the life of the client (nothing is ever dropped). A topic that is not in the registry has
 * no tables and reads as empty; reading never creates one, so a role that may only read never needs CREATE.
 */
final class PostgresTopics {

    /**
     * A topic and its tables, qualified for SQL text.
     *
     * @param name     the topic
     * @param n        its number in the registry, also the second key of its publish lock
     * @param message  its message table
     * @param content  its content table
     * @param metadata its metadata table
     */
    record Topic(String name, int n, String message, String content, String metadata) {

        /**
         * Remembers this topic, found or created and committed, for the life of the client.
         */
        Topic remember(Map<String, Topic> known) {
            Topic first = known.putIfAbsent(name, this);
            return first != null ? first : this;
        }
    }

    private final Jdbc jdbc;
    private final PostgresSchema schema;
    private final Map<String, Topic> known = new ConcurrentHashMap<>();

    PostgresTopics(Jdbc jdbc, PostgresSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /**
     * The topic, or null if it has not been written yet.
     */
    Topic find(String topic) {
        Topic found = known.get(topic);
        if (found != null) {
            return found;
        }
        return jdbc.transaction("find topic " + topic, connection -> find(connection, topic));
    }

    /**
     * The topic, created with its tables, in one transaction, if it does not exist yet.
     *
     * @throws RawdataException if the role may not create tables in the schema, or the database fails
     */
    Topic create(String topic) {
        Topic found = find(topic);
        if (found != null) {
            return found;
        }
        try {
            return jdbc.transaction("create topic " + topic, connection -> {
                try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
                    lock.setInt(1, PostgresSchema.SCHEMA_LOCK);
                    lock.setString(2, schema.name() + "." + topic);
                    lock.execute();
                }
                Topic other = find(connection, topic); // another client may have created it while this one waited
                if (other != null) {
                    return other;
                }
                int n;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + schema.table("rawdata_topic") + " (topic) VALUES (?) RETURNING n")) {
                    insert.setString(1, topic);
                    try (ResultSet rows = insert.executeQuery()) {
                        rows.next();
                        n = rows.getInt(1);
                    }
                }
                Topic created = topic(topic, n);
                try (Statement statement = connection.createStatement()) {
                    // never IF NOT EXISTS: a table already there under a new number is an error, never adopted
                    statement.addBatch("CREATE TABLE " + created.message() + " ("
                            + "id uuid PRIMARY KEY, "
                            + "position varchar COLLATE \"C\" NOT NULL, "
                            + "position_hash bigint NOT NULL GENERATED ALWAYS AS (hashtextextended(position, 0)) STORED, "
                            + "ordering_group varchar, "
                            + "sequence_number bigint NOT NULL)");
                    statement.addBatch("CREATE INDEX rawdata_" + n + "_position ON " + created.message() + " (position_hash, id)");
                    statement.addBatch("CREATE TABLE " + created.content() + " ("
                            + "id uuid NOT NULL REFERENCES " + created.message() + " (id) ON DELETE CASCADE, "
                            + "ordinal integer NOT NULL, "
                            + "name varchar NOT NULL, "
                            + "data bytea NOT NULL, "
                            + "PRIMARY KEY (id, ordinal))");
                    statement.addBatch("CREATE TABLE " + created.metadata() + " ("
                            + "name varchar COLLATE \"C\" PRIMARY KEY, "
                            + "data bytea NOT NULL)");
                    statement.executeBatch();
                }
                return created;
            }).remember(known);
        } catch (RawdataException e) {
            if (e.getCause() instanceof SQLException sql && "42501".equals(sql.getSQLState())) {
                throw new RawdataException("PostgreSQL role may not create the tables of topic " + topic + " in schema '"
                        + schema.name() + "'; grant it: GRANT USAGE, CREATE ON SCHEMA " + schema.name() + " TO <role>", e);
            }
            throw e;
        }
    }

    private Topic find(Connection connection, String topic) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT n FROM " + schema.table("rawdata_topic") + " WHERE topic = ?")) {
            select.setString(1, topic);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? topic(topic, rows.getInt(1)).remember(known) : null;
            }
        }
    }

    private Topic topic(String topic, int n) {
        return new Topic(topic, n, schema.table("rawdata_" + n + "_message"), schema.table("rawdata_" + n + "_content"),
                schema.table("rawdata_" + n + "_metadata"));
    }
}
