package io.descoped.rawdata.provider.postgres;

import io.descoped.rawdata.api.RawdataDuplicateIdException;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.spi.RawdataDuplicates;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes the messages of a topic: one row in its message table and one row per content key in its content
 * table. The tables are named by the registry ({@link PostgresTopics.Topic}), never after the topic.
 */
final class PostgresMessages {

    private static final String COLUMNS = "SELECT m.id, m.position, m.ordering_group, m.sequence_number, c.name, c.data FROM (";
    private static final String MESSAGE = "SELECT id, position, ordering_group, sequence_number FROM ";
    private static final String IN_ORDER = "ORDER BY m.id, c.ordinal";

    private PostgresMessages() {
    }

    private static String withContent(PostgresTopics.Topic topic, String messages, String order) {
        return COLUMNS + messages + ") m LEFT JOIN " + topic.content() + " c ON c.id = m.id " + order;
    }

    /**
     * Leaves out the messages that are already stored: an id is unique within a topic, so a message whose id is in the
     * topic with the same position, ordering group, sequence number and content (in the same order) was written
     * before, and writing it again is a no-op; a message repeated within the batch is written once. A different
     * message under an id already taken is refused.
     *
     * @return the messages still to write, in the batch's order
     * @throws RawdataDuplicateIdException if a message differs from the one stored, or given earlier, under its id
     */
    static List<RawdataMessage> notStored(Connection connection, PostgresTopics.Topic topic, List<RawdataMessage> messages)
            throws SQLException {
        Map<UUID, RawdataMessage> fresh = RawdataDuplicates.distinct(topic.name(), messages);
        try (PreparedStatement select = connection.prepareStatement(
                withContent(topic, MESSAGE + topic.message() + " WHERE id = ANY (?)", IN_ORDER))) {
            select.setArray(1, connection.createArrayOf("uuid", fresh.keySet().toArray()));
            for (RawdataMessage stored : read(select)) {
                if (!RawdataDuplicates.same(stored, fresh.remove(stored.id()))) {
                    throw new RawdataDuplicateIdException(topic.name(), stored.id());
                }
            }
        }
        return new ArrayList<>(fresh.values());
    }

    static List<RawdataMessage> after(Connection connection, PostgresTopics.Topic topic, UUID id, boolean inclusive, int limit)
            throws SQLException {
        String from = inclusive ? " WHERE id >= ?" : " WHERE id > ?";
        try (PreparedStatement select = connection.prepareStatement(
                withContent(topic, MESSAGE + topic.message() + from + " ORDER BY id LIMIT ?", IN_ORDER))) {
            select.setObject(1, id);
            select.setInt(2, limit);
            return read(select);
        }
    }

    static RawdataMessage last(Connection connection, PostgresTopics.Topic topic) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                withContent(topic, MESSAGE + topic.message() + " ORDER BY id DESC LIMIT 1", "ORDER BY c.ordinal"))) {
            List<RawdataMessage> messages = read(select);
            return messages.isEmpty() ? null : messages.get(0);
        }
    }

    static UUID lastId(Connection connection, PostgresTopics.Topic topic) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM " + topic.message() + " ORDER BY id DESC LIMIT 1");
             ResultSet rows = select.executeQuery()) {
            return rows.next() ? rows.getObject(1, UUID.class) : null;
        }
    }

    /**
     * The first message at a position within a range of ids, found through the index on the position's hash, which
     * PostgreSQL computes; the full position is compared too, so a hash collision never matches.
     */
    static UUID atPosition(Connection connection, PostgresTopics.Topic topic, String position, UUID from, UUID to) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM " + topic.message()
                + " WHERE position_hash = hashtextextended(?::text COLLATE \"C\", 0) AND position = ? AND id >= ? AND id < ? ORDER BY id LIMIT 1")) {
            select.setString(1, position);
            select.setString(2, position);
            select.setObject(3, from);
            select.setObject(4, to);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getObject(1, UUID.class) : null;
            }
        }
    }

    static void insert(Connection connection, PostgresTopics.Topic topic, List<RawdataMessage> messages) throws SQLException {
        try (PreparedStatement message = connection.prepareStatement(
                "INSERT INTO " + topic.message() + " (id, position, ordering_group, sequence_number) VALUES (?, ?, ?, ?)");
             PreparedStatement content = connection.prepareStatement(
                     "INSERT INTO " + topic.content() + " (id, ordinal, name, data) VALUES (?, ?, ?, ?)")) {
            boolean anyContent = false;
            for (RawdataMessage m : messages) {
                message.setObject(1, m.id());
                message.setString(2, m.position());
                message.setString(3, m.orderingGroup());
                message.setLong(4, m.sequenceNumber());
                message.addBatch();
                int ordinal = 0;
                for (String key : m.keys()) {
                    content.setObject(1, m.id());
                    content.setInt(2, ordinal++);
                    content.setString(3, key);
                    content.setBytes(4, m.get(key));
                    content.addBatch();
                    anyContent = true;
                }
            }
            message.executeBatch();
            if (anyContent) {
                content.executeBatch();
            }
        }
    }

    /**
     * Groups the rows (one per content key, or one with null content for a message without content) into messages.
     */
    private static List<RawdataMessage> read(PreparedStatement select) throws SQLException {
        List<RawdataMessage> messages = new ArrayList<>();
        try (ResultSet rows = select.executeQuery()) {
            UUID current = null;
            RawdataMessage.Builder builder = null;
            while (rows.next()) {
                UUID id = rows.getObject(1, UUID.class);
                if (!id.equals(current)) {
                    if (builder != null) {
                        messages.add(builder.build());
                    }
                    current = id;
                    builder = RawdataMessage.builder()
                            .id(id)
                            .position(rows.getString(2))
                            .orderingGroup(rows.getString(3))
                            .sequenceNumber(rows.getLong(4));
                }
                String name = rows.getString(5);
                if (name != null) {
                    builder.put(name, rows.getBytes(6));
                }
            }
            if (builder != null) {
                messages.add(builder.build());
            }
        }
        return messages;
    }
}
