package io.descoped.rawdata.provider.postgres;

import io.descoped.rawdata.api.RawdataException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * The schema the client works in ({@code postgres.schema}), its registry of topics, and its probe. Each topic has
 * tables of its own, named after its number in the registry, never after the topic:
 * <pre>
 * rawdata_topic        (topic)             a topic and its number n, created when the topic is first written
 * rawdata_probe        (word)              one word and its hash, which guards the position hash (see below)
 * rawdata_&lt;n&gt;_message  (id)                one row per message; id is the UUIDv7, ordered as PostgreSQL orders uuid;
 *                                          the raw position, and its hash, computed by PostgreSQL, indexed for seek
 * rawdata_&lt;n&gt;_content  (id, ordinal)       the named content of a message, in the order the keys were added
 * rawdata_&lt;n&gt;_metadata (name)              the metadata entries of the topic
 * </pre>
 * Nothing is ever dropped. The registry and the probe are created when missing, under an advisory lock, so clients
 * that start at the same time do not race; a role that may not create tables starts once they exist.
 * <p>
 * The position's hash is {@code hashtextextended}, PostgreSQL's own hash (not OpenSSL's, so it works in FIPS mode),
 * kept in a stored generated column. It is not a documented function: a major version could in principle change it,
 * which would leave stored hashes that seeks no longer find. The probe holds a word and its hash in the same kind of
 * column, recomputed and kept exactly when the topics' are; a client whose server hashes the word differently refuses
 * to start, naming the statements that recompute the hashes.
 */
final class PostgresSchema {

    /**
     * Advisory lock class ids ("RDPC" and the next): one for creating the registry and topics, one for publishing to
     * a topic, with the topic's number as the second key.
     */
    static final int SCHEMA_LOCK = 0x52445043;
    static final int TOPIC_LOCK = 0x52445044;

    /**
     * The word whose hash the probe keeps.
     */
    static final String PROBE_WORD = "rawdata-probe";

    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final String name;
    private final String quoted;

    /**
     * A schema by name.
     *
     * @param name an unquoted PostgreSQL identifier: lower case letters, digits and {@code _}, at most 63, not
     *             starting with a digit
     * @throws IllegalArgumentException if the name is not one
     */
    PostgresSchema(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Configuration '" + PostgresRawdataClientInitializer.SCHEMA
                    + "' must be 1 to 63 of a-z, 0-9 and _, not starting with a digit: " + name);
        }
        this.name = name;
        this.quoted = '"' + name + '"';
    }

    String name() {
        return name;
    }

    /**
     * A table of the schema, qualified and quoted where needed, for SQL text: the name comes from validated
     * configuration and the provider's own names, never from a topic.
     */
    String table(String table) {
        return quoted + "." + table;
    }

    /**
     * Checks the schema, creates the registry and the probe when missing, and checks the probe.
     *
     * @throws RawdataException if the schema does not exist, the registry is missing and cannot be created, or the
     *                          probe's hash differs from the server's
     */
    void create(Jdbc jdbc) {
        jdbc.transaction("prepare schema " + name, connection -> {
            if (!schemaExists(connection)) {
                throw new RawdataException("PostgreSQL schema '" + name + "' does not exist (" + PostgresRawdataClientInitializer.SCHEMA + ")");
            }
            if (!registryExists(connection)) {
                lock(connection, 0);
                try (Statement statement = connection.createStatement()) {
                    statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("rawdata_topic") + " ("
                            + "topic varchar(100) COLLATE \"C\" PRIMARY KEY, "
                            + "n integer GENERATED ALWAYS AS IDENTITY UNIQUE)");
                    statement.addBatch("CREATE TABLE IF NOT EXISTS " + table("rawdata_probe") + " ("
                            + "word text COLLATE \"C\" PRIMARY KEY, "
                            + "hash bigint NOT NULL GENERATED ALWAYS AS (hashtextextended(word, 0)) STORED)");
                    statement.addBatch("INSERT INTO " + table("rawdata_probe") + " (word) VALUES ('" + PROBE_WORD
                            + "') ON CONFLICT DO NOTHING");
                    statement.executeBatch();
                }
            }
            return null;
        });
        jdbc.transaction("check the position hash of schema " + name, connection -> {
            checkProbe(connection);
            return null;
        });
    }

    private boolean schemaExists(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
            select.setString(1, name);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean registryExists(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT to_regclass(?) IS NOT NULL AND to_regclass(?) IS NOT NULL")) {
            select.setString(1, table("rawdata_topic"));
            select.setString(2, table("rawdata_probe"));
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private void checkProbe(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT hash = hashtextextended(word, 0) FROM " + table("rawdata_probe") + " WHERE word = ?")) {
            select.setString(1, PROBE_WORD);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    throw new RawdataException("PostgreSQL schema '" + name + "' has no row '" + PROBE_WORD
                            + "' in rawdata_probe; restore it with: INSERT INTO " + table("rawdata_probe") + " (word) VALUES ('"
                            + PROBE_WORD + "')");
                }
                if (!rows.getBoolean(1)) {
                    throw new RawdataException("PostgreSQL hashes positions differently from when the topics of schema '"
                            + name + "' were written (hashtextextended changed, for example after pg_upgrade); seeks by"
                            + " position would miss. Recompute the hashes, for each n in " + table("rawdata_topic")
                            + ": UPDATE " + table("rawdata_<n>_message") + " SET position = position; then: UPDATE "
                            + table("rawdata_probe") + " SET word = word");
                }
            }
        }
    }

    /**
     * Takes the schema's advisory lock with a key, for the rest of the transaction.
     */
    static void lock(Connection connection, int key) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
            lock.setInt(1, SCHEMA_LOCK);
            lock.setInt(2, key);
            lock.execute();
        }
    }
}
