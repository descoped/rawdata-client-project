package io.descoped.rawdata.provider.postgres;

import java.util.List;

/**
 * The SQL of one topic, built once when the topic is found or created. The text holds only the configured schema
 * (validated by {@link PostgresSchema}) and the topic's number from the registry; the topic name and every value are
 * bind parameters.
 */
final class PostgresTopicSql {

    private static final String TABLE_PREFIX = "rawdata_";
    private static final String CREATE_TABLE = "CREATE TABLE ";
    private static final String COLUMNS = "SELECT m.id, m.position, m.ordering_group, m.sequence_number, c.name, c.data FROM (";
    private static final String MESSAGE_COLUMNS = "SELECT id, position, ordering_group, sequence_number FROM ";
    private static final String IN_ORDER = "ORDER BY m.id, c.ordinal";

    /** The topic's tables and index, created in one transaction at its first write. */
    final List<String> create;
    /** The topic's greatest id. */
    final String lastId;
    /** One message row. */
    final String insertMessage;
    /** One content row. */
    final String insertContent;
    /** Up to a number of messages after an id, with their content. */
    final String after;
    /** Up to a number of messages from an id, with their content. */
    final String from;
    /** The message with the greatest id, with its content. */
    final String last;
    /** The messages with the given ids, with their content. */
    final String withIds;
    /** The first id at a position within a range of ids, through the index on the position's hash. */
    final String atPosition;
    /** The metadata keys, sorted. */
    final String metadataKeys;
    /** One metadata value. */
    final String metadataGet;
    /** Writes one metadata value, replacing any. */
    final String metadataPut;
    /** Removes one metadata entry. */
    final String metadataRemove;

    PostgresTopicSql(PostgresSchema schema, int n) {
        String message = schema.table(TABLE_PREFIX + n + "_message");
        String content = schema.table(TABLE_PREFIX + n + "_content");
        String metadata = schema.table(TABLE_PREFIX + n + "_metadata");
        // never IF NOT EXISTS: a table already there under a new number is an error, never adopted
        create = List.of(
                CREATE_TABLE + message + " ("
                        + "id uuid PRIMARY KEY, "
                        + "position varchar COLLATE \"C\" NOT NULL, "
                        + "position_hash bigint NOT NULL GENERATED ALWAYS AS (hashtextextended(position, 0)) STORED, "
                        + "ordering_group varchar, "
                        + "sequence_number bigint NOT NULL)",
                "CREATE INDEX " + TABLE_PREFIX + n + "_position ON " + message + " (position_hash, id)",
                CREATE_TABLE + content + " ("
                        + "id uuid NOT NULL REFERENCES " + message + " (id) ON DELETE CASCADE, "
                        + "ordinal integer NOT NULL, "
                        + "name varchar NOT NULL, "
                        + "data bytea NOT NULL, "
                        + "PRIMARY KEY (id, ordinal))",
                CREATE_TABLE + metadata + " ("
                        + "name varchar COLLATE \"C\" PRIMARY KEY, "
                        + "data bytea NOT NULL)");
        lastId = "SELECT id FROM " + message + " ORDER BY id DESC LIMIT 1";
        insertMessage = "INSERT INTO " + message + " (id, position, ordering_group, sequence_number) VALUES (?, ?, ?, ?)";
        insertContent = "INSERT INTO " + content + " (id, ordinal, name, data) VALUES (?, ?, ?, ?)";
        String withContent = ") m LEFT JOIN " + content + " c ON c.id = m.id ";
        after = COLUMNS + MESSAGE_COLUMNS + message + " WHERE id > ? ORDER BY id LIMIT ?" + withContent + IN_ORDER;
        from = COLUMNS + MESSAGE_COLUMNS + message + " WHERE id >= ? ORDER BY id LIMIT ?" + withContent + IN_ORDER;
        last = COLUMNS + MESSAGE_COLUMNS + message + " ORDER BY id DESC LIMIT 1" + withContent + "ORDER BY c.ordinal";
        withIds = COLUMNS + MESSAGE_COLUMNS + message + " WHERE id = ANY (?)" + withContent + IN_ORDER;
        // COLLATE "C" as in the column, so the hash depends on the bytes alone; the full position is compared too
        atPosition = "SELECT id FROM " + message + " WHERE position_hash = hashtextextended(?::text COLLATE \"C\", 0)"
                + " AND position = ? AND id >= ? AND id < ? ORDER BY id LIMIT 1";
        metadataKeys = "SELECT name FROM " + metadata + " ORDER BY name";
        metadataGet = "SELECT data FROM " + metadata + " WHERE name = ?";
        metadataPut = "INSERT INTO " + metadata + " (name, data) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET data = EXCLUDED.data";
        metadataRemove = "DELETE FROM " + metadata + " WHERE name = ?";
    }
}
