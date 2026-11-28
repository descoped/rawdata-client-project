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
     * A topic, its number and its SQL.
     *
     * @param name the topic
     * @param n    its number in the registry, which names its tables and is the second key of its publish lock
     * @param sql  the statements on its tables
     */
    record Topic(String name, int n, PostgresTopicSql sql) {

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
                PostgresSchema.lock(connection, schema.name() + "." + topic);
                Topic other = find(connection, topic); // another client may have created it while this one waited
                return other != null ? other : createIn(connection, topic);
            }).remember(known);
        } catch (RawdataException e) {
            if (e.getCause() instanceof SQLException sql && "42501".equals(sql.getSQLState())) {
                throw new RawdataException("PostgreSQL role may not create topic " + topic + " in schema '" + schema.name()
                        + "' (" + sql.getMessage() + "); a writer needs: GRANT USAGE, CREATE ON SCHEMA " + schema.name()
                        + " TO <role>, and, if another role owns the registry: GRANT SELECT, INSERT ON "
                        + schema.table("rawdata_topic") + " TO <role>", e);
            }
            throw e;
        }
    }

    /**
     * Registers a topic and creates its tables in the caller's transaction, which holds the topic's creation lock
     * ({@link PostgresSchema#lock(Connection, String)}) and has found it missing. The topic is not remembered: the
     * caller's transaction may still roll back.
     */
    Topic createIn(Connection connection, String topic) throws SQLException {
        int n;
        try (PreparedStatement insert = connection.prepareStatement(schema.insertTopic())) {
            insert.setString(1, topic);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                n = rows.getInt(1);
            }
        }
        Topic created = new Topic(topic, n, new PostgresTopicSql(schema, n));
        try (Statement statement = connection.createStatement()) {
            for (String ddl : created.sql().create) {
                statement.addBatch(ddl);
            }
            statement.executeBatch();
        }
        return created;
    }

    /**
     * Whether a topic is in the registry, read in the caller's transaction.
     */
    boolean registered(Connection connection, String topic) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(schema.findTopic())) {
            select.setString(1, topic);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    private Topic find(Connection connection, String topic) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(schema.findTopic())) {
            select.setString(1, topic);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                int n = rows.getInt(1);
                return new Topic(topic, n, new PostgresTopicSql(schema, n)).remember(known);
            }
        }
    }
}
