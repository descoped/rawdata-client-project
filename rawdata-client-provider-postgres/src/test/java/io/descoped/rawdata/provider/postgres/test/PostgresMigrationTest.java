package io.descoped.rawdata.provider.postgres.test;

import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataException;
import io.descoped.rawdata.api.RawdataIds;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The move of 3.1's shared tables into tables of their own ({@code postgres.migrate-3.1}), on tables made by 3.1's own
 * DDL in a schema of each test's own.
 */
@Timeout(120)
class PostgresMigrationTest {

    private static final String MIGRATE = "postgres.migrate-3.1";

    /**
     * The tables of rawdata-client-provider-postgres 3.1.0, as its {@code PostgresSchema} created them.
     */
    private static final List<String> DDL_31 = List.of(
            """
            CREATE TABLE IF NOT EXISTS rawdata_message (
                topic           varchar(100) NOT NULL,
                id              uuid         NOT NULL,
                position        varchar      NOT NULL,
                ordering_group  varchar,
                sequence_number bigint       NOT NULL,
                PRIMARY KEY (topic, id)
            )""",
            "CREATE INDEX IF NOT EXISTS rawdata_message_position ON rawdata_message (topic, position)",
            """
            CREATE TABLE IF NOT EXISTS rawdata_content (
                topic   varchar(100) NOT NULL,
                id      uuid         NOT NULL,
                ordinal integer      NOT NULL,
                name    varchar      NOT NULL,
                data    bytea        NOT NULL,
                PRIMARY KEY (topic, id, ordinal),
                UNIQUE (topic, id, name),
                FOREIGN KEY (topic, id) REFERENCES rawdata_message (topic, id) ON DELETE CASCADE
            )""",
            """
            CREATE TABLE IF NOT EXISTS rawdata_metadata (
                topic varchar(100) NOT NULL,
                name  varchar      NOT NULL,
                data  bytea        NOT NULL,
                PRIMARY KEY (topic, name)
            )""");

    private static void create31(String schema) throws SQLException {
        try (Connection connection = Postgres18.connect(); Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema); // 3.1 created its tables unqualified
            for (String ddl : DDL_31) {
                statement.execute(ddl);
            }
        }
    }

    /**
     * Writes a message as 3.1 did: one message row and one content row per key, in the order given.
     */
    private static void write31(String schema, String topic, UUID id, String position, String... keys) throws SQLException {
        try (Connection connection = Postgres18.connect()) {
            try (PreparedStatement message = connection.prepareStatement("INSERT INTO " + schema
                    + ".rawdata_message (topic, id, position, ordering_group, sequence_number) VALUES (?, ?, ?, ?, ?)")) {
                message.setString(1, topic);
                message.setObject(2, id);
                message.setString(3, position);
                message.setString(4, "group-" + position);
                message.setLong(5, 7);
                message.executeUpdate();
            }
            try (PreparedStatement content = connection.prepareStatement("INSERT INTO " + schema
                    + ".rawdata_content (topic, id, ordinal, name, data) VALUES (?, ?, ?, ?, ?)")) {
                for (int i = 0; i < keys.length; i++) {
                    content.setString(1, topic);
                    content.setObject(2, id);
                    content.setInt(3, i);
                    content.setString(4, keys[i]);
                    content.setBytes(5, (position + "/" + keys[i]).getBytes(StandardCharsets.UTF_8));
                    content.executeUpdate();
                }
            }
        }
    }

    private static void metadata31(String schema, String topic, String name, byte[] data) throws SQLException {
        try (Connection connection = Postgres18.connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + schema + ".rawdata_metadata (topic, name, data) VALUES (?, ?, ?)")) {
            insert.setString(1, topic);
            insert.setString(2, name);
            insert.setBytes(3, data);
            insert.executeUpdate();
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = Postgres18.connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection connection = Postgres18.connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static boolean exists(String table) throws SQLException {
        return count("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname || '.' || c.relname = '" + table + "'") == 1;
    }

    private static Map<String, String> migrating(Map<String, String> configuration) {
        Map<String, String> migrating = new java.util.HashMap<>(configuration);
        migrating.put(MIGRATE, "true");
        return migrating;
    }

    private static List<RawdataMessage> readAll(RawdataClient client, String topic) throws InterruptedException {
        List<RawdataMessage> read = new ArrayList<>();
        try (RawdataConsumer consumer = client.consumer(topic)) {
            RawdataMessage message;
            while ((message = consumer.receive(200, TimeUnit.MILLISECONDS)) != null) {
                read.add(message);
            }
        }
        return read;
    }

    /**
     * A client refuses to start on a schema that holds 3.1 data, naming the key that moves it, and changes nothing;
     * so do readers, which would otherwise find every topic empty.
     */
    @Test
    void aClientRefusesToStartOn31DataWithoutTheKey() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        create31(schema);
        write31(schema, "a", RawdataIds.generate(), "p1", "k");
        RawdataException refused = assertThrows(RawdataException.class, () -> RawdataClient.create(configuration));
        assertTrue(refused.getMessage().contains(MIGRATE + "=true"), refused.getMessage());
        assertTrue(exists(schema + ".rawdata_message"), "nothing is renamed");
        assertFalse(exists(schema + ".rawdata_v31_migration"));
    }

    /**
     * Every topic is moved, messages with their ids, order, positions, ordering groups, sequence numbers and content
     * in key order, and metadata, also of a topic with only metadata. A seek by position finds a moved message, a
     * publish continues after the last moved id, and the old tables are kept under new names.
     */
    @Test
    void movesEveryTopicKeepingIdsOrderContentAndMetadata() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        create31(schema);
        long base = System.currentTimeMillis() - 60_000;
        UUID a1 = RawdataIds.beginningOf(base);
        UUID a2 = RawdataIds.beginningOf(base + 1000);
        UUID a3 = RawdataIds.beginningOf(base + 2000);
        write31(schema, "a", a2, "p2", "z", "a");
        write31(schema, "a", a1, "p1", "k");
        write31(schema, "a", a3, "p3");
        write31(schema, "b", RawdataIds.beginningOf(base), "only");
        metadata31(schema, "a", "checkpoint", new byte[]{1, 2});
        metadata31(schema, "c", "owner", new byte[]{3});

        try (RawdataClient client = RawdataClient.create(migrating(configuration))) {
            List<RawdataMessage> a = readAll(client, "a");
            assertEquals(List.of(a1, a2, a3), a.stream().map(RawdataMessage::id).toList(), "ids in id order");
            assertEquals(List.of("p1", "p2", "p3"), a.stream().map(RawdataMessage::position).toList());
            RawdataMessage second = a.get(1);
            assertEquals("group-p2", second.orderingGroup());
            assertEquals(7, second.sequenceNumber());
            assertEquals(List.of("z", "a"), List.copyOf(second.keys()), "content keys in the order they were added");
            assertArrayEquals("p2/z".getBytes(StandardCharsets.UTF_8), second.get("z"));
            assertEquals(Set.of(), a.get(2).keys());
            assertEquals(1, readAll(client, "b").size());
            assertArrayEquals(new byte[]{1, 2}, client.metadata("a").get("checkpoint"));
            assertArrayEquals(new byte[]{3}, client.metadata("c").get("owner"));
            assertEquals(Set.of(), client.metadata("b").keys());

            try (RawdataConsumer seek = client.consumer("a", "p2", true, base + 1000, Duration.ofSeconds(10))) {
                RawdataMessage found = seek.receive(5, TimeUnit.SECONDS);
                assertNotNull(found);
                assertEquals(a2, found.id(), "a seek by position finds a moved message");
            }
            try (RawdataProducer producer = client.producer("a")) {
                producer.publish(RawdataMessage.builder().position("p4").build());
            }
            RawdataMessage last = client.lastMessage("a");
            assertEquals("p4", last.position());
            assertTrue(RawdataIds.ORDER.compare(last.id(), a3) > 0, "a publish continues after the last moved id");
        }
        for (String kind : List.of("message", "content", "metadata")) {
            assertFalse(exists(schema + ".rawdata_" + kind));
            assertTrue(exists(schema + ".rawdata_v31_" + kind), "the old tables are kept: " + kind);
        }
        assertTrue(exists(schema + ".rawdata_v31_migrated"));
        assertEquals(4, count("SELECT count(*) FROM " + schema + ".rawdata_v31_message"));
        try (RawdataClient again = RawdataClient.create(configuration)) {
            assertEquals(4, readAll(again, "a").size(), "after the move a client starts without the key");
        }
    }

    /**
     * A topic that cannot be moved is listed after the others are moved; while it is left a client without the key
     * refuses to start, and a start with the key again moves it and finishes the move.
     */
    @Test
    void aTopicThatCannotMoveIsListedAndTheMoveResumes() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        Map<String, String> withKey = migrating(configuration);
        create31(schema);
        write31(schema, "a", RawdataIds.generate(), "pa", "k");
        write31(schema, "b", RawdataIds.generate(), "pb", "k");
        // topics move in name order: a gets number 1, whose content table is taken
        execute("CREATE TABLE " + schema + ".rawdata_1_content (blocker int)");
        RawdataException failed = assertThrows(RawdataException.class, () -> RawdataClient.create(withKey));
        assertTrue(failed.getMessage().contains("could not move 1 topics") && failed.getMessage().contains(": a."),
                failed.getMessage());
        assertEquals(1, count("SELECT count(*) FROM " + schema + ".rawdata_v31_migration WHERE done"), "b is moved");

        RawdataException unfinished = assertThrows(RawdataException.class, () -> RawdataClient.create(configuration));
        assertTrue(unfinished.getMessage().contains("not finished"), unfinished.getMessage());

        execute("DROP TABLE " + schema + ".rawdata_1_content");
        try (RawdataClient client = RawdataClient.create(withKey)) {
            assertEquals(List.of("pa"), readAll(client, "a").stream().map(RawdataMessage::position).toList());
            assertEquals(List.of("pb"), readAll(client, "b").stream().map(RawdataMessage::position).toList());
        }
        assertFalse(exists(schema + ".rawdata_v31_migration"), "a finished move renames its progress table");
        assertEquals(2, count("SELECT count(*) FROM " + schema + ".rawdata_v31_migrated WHERE done"));
        RawdataClient.create(configuration).close();
    }

    /**
     * After the fence a 3.1 writer that still runs fails, and one that restarted and made its tables again stops
     * every client of this version from starting, so what it writes is never lost unseen.
     */
    @Test
    void a31WriterAfterTheMoveIsNeverLostUnseen() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        Map<String, String> withKey = migrating(configuration);
        create31(schema);
        write31(schema, "a", RawdataIds.generate(), "pa");
        RawdataClient.create(migrating(configuration)).close();
        UUID late = RawdataIds.generate();
        assertThrows(SQLException.class, () -> write31(schema, "a", late, "late"),
                "a running 3.1 writer fails: its tables are gone");

        create31(schema); // a restarted 3.1 client makes its tables again
        write31(schema, "a", RawdataIds.generate(), "late");
        RawdataException refused = assertThrows(RawdataException.class, () -> RawdataClient.create(configuration));
        assertTrue(refused.getMessage().contains("3.1 tables again"), refused.getMessage());
        assertThrows(RawdataException.class, () -> RawdataClient.create(withKey));
    }

    /**
     * A metadata key over 127 bytes, which 3.1 took and this version does not read, refuses the move and changes
     * nothing; the message names the topic and the key.
     */
    @Test
    void aLongMetadataKeyRefusesTheMoveChangingNothing() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        Map<String, String> withKey = migrating(configuration);
        create31(schema);
        write31(schema, "a", RawdataIds.generate(), "pa");
        metadata31(schema, "a", "long-" + "k".repeat(200), new byte[]{1});
        RawdataException refused = assertThrows(RawdataException.class, () -> RawdataClient.create(withKey));
        assertTrue(refused.getMessage().contains("a: long-kkk"), refused.getMessage());
        assertTrue(exists(schema + ".rawdata_message"), "nothing is renamed");
        assertEquals(0, count("SELECT count(*) FROM " + schema + ".rawdata_topic"));
    }

    /**
     * A topic that both 3.1 and this version wrote is not merged: the move refuses, naming it, and changes nothing.
     */
    @Test
    void aTopicWrittenByBothVersionsRefusesTheMove() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        Map<String, String> withKey = migrating(configuration);
        try (RawdataClient client = RawdataClient.create(configuration); RawdataProducer producer = client.producer("a")) {
            producer.publish(RawdataMessage.builder().position("new").build());
        }
        create31(schema);
        write31(schema, "a", RawdataIds.generate(), "old");
        RawdataException refused = assertThrows(RawdataException.class, () -> RawdataClient.create(withKey));
        assertTrue(refused.getMessage().contains("both by 3.1 and by this version"), refused.getMessage());
        assertTrue(exists(schema + ".rawdata_message"), "nothing is renamed");
    }

    /**
     * Clients started with the key at the same time fence once and move each topic once.
     */
    @Test
    void clientsStartedTogetherMoveEachTopicOnce() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        Map<String, String> withKey = migrating(configuration);
        create31(schema);
        for (int t = 0; t < 10; t++) {
            for (int m = 0; m < 5; m++) {
                write31(schema, "t" + t, RawdataIds.generate(), "p" + m, "k");
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Void>> started = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Callable<Void> start = () -> {
                    RawdataClient.create(withKey).close();
                    return null;
                };
                started.add(pool.submit(start));
            }
            for (Future<Void> each : started) {
                each.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(10, count("SELECT count(*) FROM " + schema + ".rawdata_topic"));
        try (RawdataClient client = RawdataClient.create(configuration)) {
            for (int t = 0; t < 10; t++) {
                assertEquals(5, readAll(client, "t" + t).size(), "topic t" + t);
            }
        }
    }
}
