package io.descoped.rawdata.provider.postgres.test;

import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataClosedException;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataException;
import io.descoped.rawdata.api.RawdataIds;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataProducer;
import io.descoped.rawdata.api.RawdataWriterConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider's internals, which the TCK cannot see: the stored layout, the topic lock, privileges, the start-up of
 * the schema and the pool.
 */
@Timeout(120)
class PostgresProviderTest {

    private static String topic() {
        return "pg-" + UUID.randomUUID();
    }

    private static RawdataClient client() {
        return RawdataClient.create(Postgres18.configuration());
    }

    /**
     * A table of a topic in a schema: rawdata_&lt;n&gt;_&lt;kind&gt;, n from the registry.
     */
    private static String table(String schema, String topic, String kind) throws Exception {
        return schema + ".rawdata_" + number(schema, topic) + "_" + kind;
    }

    private static String table(String topic, String kind) throws Exception {
        return table("public", topic, kind);
    }

    private static int number(String schema, String topic) throws Exception {
        try (Connection connection = Postgres18.connect();
             PreparedStatement select = connection.prepareStatement("SELECT n FROM " + schema + ".rawdata_topic WHERE topic = ?")) {
            select.setString(1, topic);
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next(), "topic " + topic + " is in the registry of " + schema);
                return rows.getInt(1);
            }
        }
    }

    @Test
    void storedIdsArePostgresUuidV7() throws Exception {
        String topic = topic();
        try (RawdataClient client = client(); RawdataProducer producer = client.producer(topic)) {
            producer.publish(RawdataMessage.builder().position("a").put("k", new byte[]{1}).build());
            RawdataMessage message = client.lastMessage(topic);
            try (Connection connection = Postgres18.connect();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT uuid_extract_version(id), (extract(epoch FROM uuid_extract_timestamp(id)) * 1000)::bigint FROM "
                                 + table(topic, "message"))) {
                try (ResultSet rows = select.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(7, rows.getInt(1));
                    assertEquals(message.timestamp(), rows.getLong(2));
                }
            }
        }
    }

    @Test
    void postgresOrdersIdsAsRawdataIdsDoes() throws Exception {
        List<UUID> ids = new ArrayList<>();
        UUID id = RawdataIds.beginningOf(System.currentTimeMillis());
        for (int i = 0; i < 500; i++) {
            ids.add(id = RawdataIds.next(id));
        }
        ids.add(UUID.fromString("7fffffff-ffff-7fff-bfff-ffffffffffff"));
        ids.add(UUID.fromString("80000000-0000-7000-8000-000000000000"));
        List<UUID> expected = new ArrayList<>(ids);
        expected.sort(RawdataIds.ORDER);
        try (Connection connection = Postgres18.connect(); Statement statement = connection.createStatement()) {
            StringBuilder values = new StringBuilder();
            for (UUID each : ids) {
                values.append(values.isEmpty() ? "" : ",").append("('").append(each).append("'::uuid)");
            }
            List<UUID> sorted = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT u FROM (VALUES " + values + ") v(u) ORDER BY u")) {
                while (rows.next()) {
                    sorted.add(rows.getObject(1, UUID.class));
                }
            }
            assertEquals(expected, sorted);
        }
    }

    /**
     * A publish that waits for the topic lock reads the last id after the lock holder's commit, also when the
     * database defaults to REPEATABLE READ (whose snapshot would be taken before the wait): the pool pins READ
     * COMMITTED.
     */
    @Test
    void publishReadsTheLastIdAfterWaitingForTheTopicLock() throws Exception {
        execute("ALTER ROLE rdc SET default_transaction_isolation = 'repeatable read'");
        String topic = topic();
        UUID future = RawdataIds.next(RawdataIds.beginningOf(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)));
        try (RawdataClient client = client(); RawdataProducer producer = client.producer(topic)) {
            client.metadata(topic).put("created", new byte[0]); // the topic and its tables exist
            Connection holder = holdTopicLock(topic);
            try (PreparedStatement insert = holder.prepareStatement(
                    "INSERT INTO " + table(topic, "message") + " (id, position, sequence_number) VALUES (?, 'holder', 0)")) {
                insert.setObject(1, future);
                insert.executeUpdate();
            }
            CompletableFuture<Void> publish = CompletableFuture.runAsync(
                    () -> producer.publish(RawdataMessage.builder().position("after-holder").build()));
            awaitTopicLockWaiter();
            assertFalse(publish.isDone(), "the publish waits for the topic lock");
            holder.commit();
            holder.close();
            publish.get(30, TimeUnit.SECONDS);
            RawdataMessage last = client.lastMessage(topic);
            assertEquals("after-holder", last.position());
            assertTrue(RawdataIds.ORDER.compare(last.id(), future) > 0, "the id follows the holder's commit");
        } finally {
            execute("ALTER ROLE rdc RESET default_transaction_isolation");
        }
    }

    /**
     * The advisory lock class of a topic, as PostgresSchema.TOPIC_LOCK.
     */
    private static final int TOPIC_LOCK = 0x52445044;

    /**
     * Waits until a session of the provider waits for a topic lock, as {@code pg_locks} shows it.
     */
    private static void awaitTopicLockWaiter() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (count("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid"
                + " WHERE l.locktype = 'advisory' AND l.classid = " + TOPIC_LOCK + " AND NOT l.granted"
                + " AND a.application_name = 'rawdata-postgres'") == 0) {
            assertTrue(System.nanoTime() < deadline, "no publish waits for the topic lock");
        }
    }

    private static long count(String sql) throws Exception {
        try (Connection connection = Postgres18.connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /**
     * Holds a topic's lock, keyed by its number in the registry, on a connection of its own, as another process's
     * publish would. The topic must exist.
     */
    private static Connection holdTopicLock(String topic) throws Exception {
        int n = number("public", topic);
        Connection holder = Postgres18.connect();
        holder.setAutoCommit(false);
        try (PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
            lock.setInt(1, TOPIC_LOCK);
            lock.setInt(2, n);
            lock.execute();
        }
        return holder;
    }

    /**
     * Makes a topic exist, with its tables, without a message.
     */
    private static void create(RawdataClient client, String topic) {
        client.metadata(topic).put("created", new byte[0]);
    }

    /**
     * A publish waiting for its turn behind another producer of the client writes nothing when its producer closes
     * meanwhile, and throws RawdataClosedException.
     */
    @Test
    void aPublishThatWaitedForItsTurnWritesNothingOnceItsProducerClosed() throws Exception {
        String topic = topic();
        try (RawdataClient client = client(); RawdataProducer first = client.producer(topic)) {
            create(client, topic);
            Connection holder = holdTopicLock(topic);
            RawdataProducer second = client.producer(topic); // closed by the test, while its publish waits
            CompletableFuture<Void> waitsInTheDatabase = CompletableFuture.runAsync(
                    () -> first.publish(RawdataMessage.builder().position("first").build()));
            awaitTopicLockWaiter(); // the first publish has the client's turn and waits for the topic lock
            CompletableFuture<Void> secondPublish = new CompletableFuture<>();
            Thread waitsForItsTurn = new Thread(() -> {
                try {
                    second.publish(RawdataMessage.builder().position("second").build());
                    secondPublish.complete(null);
                } catch (RuntimeException e) {
                    secondPublish.completeExceptionally(e);
                }
            });
            waitsForItsTurn.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (waitsForItsTurn.getState() != Thread.State.WAITING) { // parked on the client's turn
                assertTrue(System.nanoTime() < deadline, "the second publish does not wait for its turn");
                Thread.onSpinWait();
            }
            second.close();
            holder.commit();
            waitsInTheDatabase.get(30, TimeUnit.SECONDS);
            ExecutionException failed = assertThrows(ExecutionException.class, () -> secondPublish.get(30, TimeUnit.SECONDS));
            assertInstanceOf(RawdataClosedException.class, failed.getCause());
            assertEquals("first", client.lastMessage(topic).position(), "the closed producer wrote nothing");
            holder.close();
        }
    }

    /**
     * Closing the client while a publish waits in the database aborts it: the publish throws RawdataClosedException,
     * with the database's failure as the cause, and writes nothing.
     */
    @Test
    void closingTheClientDuringAPublishReportsItClosed() throws Exception {
        String topic = topic();
        RawdataClient client = client();
        create(client, topic);
        try (Connection holder = holdTopicLock(topic)) {
            RawdataProducer producer = client.producer(topic);
            CompletableFuture<Void> publish = CompletableFuture.runAsync(
                    () -> producer.publish(RawdataMessage.builder().position("aborted").build()));
            awaitTopicLockWaiter();
            client.close();
            ExecutionException failed = assertThrows(ExecutionException.class, () -> publish.get(30, TimeUnit.SECONDS));
            RawdataClosedException closed = assertInstanceOf(RawdataClosedException.class, failed.getCause());
            assertInstanceOf(SQLException.class, closed.getCause());
            holder.commit();
        } finally {
            client.close();
        }
        assertEquals(0, count("SELECT count(*) FROM " + table(topic, "message")));
    }

    /**
     * With a lock timeout, a publish that waits longer for the topic lock fails, writes nothing, and the next publish,
     * once the lock is free, goes on as if the failed one never ran.
     */
    @Test
    void aPublishGivesUpAfterTheLockTimeoutAndWritesNothing() throws Exception {
        Map<String, String> configuration = new HashMap<>(Postgres18.configuration());
        configuration.put("rawdata.postgres.publish.lock-timeout-ms", "200");
        String topic = topic();
        try (RawdataClient client = RawdataClient.create(configuration); RawdataProducer producer = client.producer(topic)) {
            producer.publish(RawdataMessage.builder().position("before").build());
            try (Connection holder = holdTopicLock(topic)) {
                RawdataMessage late = RawdataMessage.builder().position("late").build();
                RawdataException failed = assertThrows(RawdataException.class, () -> producer.publish(late));
                assertTrue(failed.getMessage().contains("SQLState 55P03"), failed.getMessage());
                holder.rollback();
            }
            producer.publish(RawdataMessage.builder().position("after").build());
            assertEquals("after", client.lastMessage(topic).position());
            assertEquals(2, count("SELECT count(*) FROM " + table(topic, "message")));
        }
    }

    /**
     * The TLS mode reaches the driver: {@code require}, in any case, fails against the test server, which has no TLS;
     * a mode pgjdbc does not know, and a root certificate without a verifying mode, are refused before connecting.
     * TCP keep-alive is taken.
     */
    @Test
    void theTlsModeAndKeepAliveReachTheDriver() {
        Map<String, String> require = new HashMap<>(Postgres18.configuration());
        require.put("postgres.driver.ssl-mode", "require");
        RawdataException noTls = assertThrows(RawdataException.class, () -> RawdataClient.create(require));
        assertTrue(String.valueOf(noTls.getCause()).contains("SSL"), String.valueOf(noTls.getCause()));
        Map<String, String> unknown = new HashMap<>(Postgres18.configuration());
        unknown.put("postgres.driver.ssl-mode", "always");
        assertThrows(IllegalArgumentException.class, () -> RawdataClient.create(unknown));
        Map<String, String> plain = new HashMap<>(Postgres18.configuration());
        plain.put("postgres.driver.ssl-mode", "disable");
        plain.put("postgres.driver.tcp-keep-alive", "true");
        try (RawdataClient client = RawdataClient.create(plain)) {
            assertNull(client.lastMessage(topic()));
        }
        Map<String, String> upperCase = new HashMap<>(Postgres18.configuration());
        upperCase.put("postgres.driver.ssl-mode", "REQUIRE");
        assertThrows(RawdataException.class, () -> RawdataClient.create(upperCase), "a mode in any case reaches the driver");
        Map<String, String> unverifiedRoot = new HashMap<>(Postgres18.configuration());
        unverifiedRoot.put("postgres.driver.ssl-root-cert", "/etc/ssl/root.crt");
        unverifiedRoot.put("postgres.driver.ssl-mode", "require");
        assertThrows(IllegalArgumentException.class, () -> RawdataClient.create(unverifiedRoot), "a root certificate would be ignored");
        Map<String, String> verifiedRoot = new HashMap<>(Postgres18.configuration());
        verifiedRoot.put("postgres.driver.ssl-root-cert", "/etc/ssl/root.crt");
        verifiedRoot.put("postgres.driver.ssl-mode", "verify-full");
        RawdataException verifying = assertThrows(RawdataException.class, () -> RawdataClient.create(verifiedRoot),
                "with verify-full the root certificate passes the check and the driver needs TLS");
        assertTrue(String.valueOf(verifying.getCause()).contains("SSL"), String.valueOf(verifying.getCause()));
        Map<String, String> notABoolean = new HashMap<>(Postgres18.configuration());
        notABoolean.put("postgres.driver.tcp-keep-alive", "yes");
        assertThrows(IllegalArgumentException.class, () -> RawdataClient.create(notABoolean));
    }

    /**
     * A database failure names the operation and the SQLState, and keeps the SQL exception as the cause: a role that
     * may not read the registry fails when the client starts.
     */
    @Test
    void aDatabaseFailureNamesTheOperationAndTheSqlState() throws Exception {
        client().close(); // the registry exists
        execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rdc_noread') THEN "
                + "CREATE ROLE rdc_noread LOGIN PASSWORD 'noread'; END IF; END $$");
        execute("GRANT USAGE ON SCHEMA public TO rdc_noread");
        Map<String, String> configuration = new HashMap<>(Postgres18.configuration());
        configuration.put("postgres.driver.user", "rdc_noread");
        configuration.put("postgres.driver.password", "noread");
        RawdataException failed = assertThrows(RawdataException.class, () -> RawdataClient.create(configuration));
        assertTrue(failed.getMessage().contains("check the position hash of schema public"), failed.getMessage());
        assertTrue(failed.getMessage().contains("SQLState 42501"), failed.getMessage());
        assertInstanceOf(SQLException.class, failed.getCause());
    }

    /**
     * A publish wakes the client's waiting consumers of the topic, which read at once instead of at the next poll (an
     * hour here); with {@code rawdata.postgres.consumer.wake-on-publish} false, they read again only at their
     * deadline. (The lower bound of that wait fails only on a spurious wake-up of {@code Object.wait}.)
     */
    @Test
    void aPublishWakesTheClientsWaitingConsumersUnlessConfiguredOff() throws Exception {
        for (boolean wake : new boolean[]{true, false}) {
            Map<String, String> configuration = new HashMap<>(Postgres18.configuration());
            configuration.put("rawdata.postgres.consumer.prefetch-poll-interval-when-empty", String.valueOf(TimeUnit.HOURS.toMillis(1)));
            configuration.put("rawdata.postgres.consumer.wake-on-publish", String.valueOf(wake));
            String topic = topic();
            try (RawdataClient client = RawdataClient.create(configuration); RawdataProducer producer = client.producer(topic);
                 RawdataConsumer consumer = client.consumer(topic)) {
                CompletableFuture<RawdataMessage> received = new CompletableFuture<>();
                Thread receiver = new Thread(() -> {
                    try {
                        received.complete(consumer.receive(wake ? 30 : 3, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        received.completeExceptionally(e);
                    }
                });
                receiver.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (receiver.getState() != Thread.State.TIMED_WAITING) { // the receive found nothing and waits
                    assertTrue(System.nanoTime() < deadline, "the receive does not wait");
                    Thread.onSpinWait();
                }
                long published = System.nanoTime();
                producer.publish(RawdataMessage.builder().position("woken").build());
                RawdataMessage message = received.get(60, TimeUnit.SECONDS);
                assertEquals("woken", message.position());
                if (!wake) { // the receive reads again only at its own deadline, 3 s after it began to wait
                    long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - published);
                    assertTrue(waited >= 1000, "without the wake-up, the receive waits for its deadline: " + waited + " ms");
                }
            }
        }
    }

    /**
     * The grants of a schema, as the docs give them: a writer with CREATE on the schema creates topics; a reader with
     * USAGE and the default SELECT reads them, and reading a topic not written yet needs nothing more; the reader may
     * not create a topic, and is told which grant it lacks.
     */
    @Test
    void aReaderReadsWhatTheWriterCreatesAndCannotCreate() throws Exception {
        Map<String, String> writer = Postgres18.configurationInNewSchema();
        String schema = writer.get("postgres.schema");
        execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rdc_reader') THEN "
                + "CREATE ROLE rdc_reader LOGIN PASSWORD 'reader'; END IF; END $$");
        execute("GRANT USAGE ON SCHEMA " + schema + " TO rdc_reader");
        execute("ALTER DEFAULT PRIVILEGES FOR ROLE rdc IN SCHEMA " + schema + " GRANT SELECT ON TABLES TO rdc_reader");
        String topic = topic();
        try (RawdataClient client = RawdataClient.create(writer); RawdataProducer producer = client.producer(topic)) {
            producer.publish(RawdataMessage.builder().position("a").put("k", new byte[]{1}).build());
            client.metadata(topic).put("checkpoint", new byte[]{2});
        }
        Map<String, String> reader = new HashMap<>(writer);
        reader.put("postgres.driver.user", "rdc_reader");
        reader.put("postgres.driver.password", "reader");
        try (RawdataClient client = RawdataClient.create(reader); RawdataConsumer consumer = client.consumer(topic)) {
            assertEquals("a", consumer.receive(5, TimeUnit.SECONDS).position());
            assertArrayEquals(new byte[]{2}, client.metadata(topic).get("checkpoint"));
            String unwritten = topic();
            assertNull(client.lastMessage(unwritten));
            assertEquals(Set.of(), client.metadata(unwritten).keys());
            client.metadata(unwritten).remove("k");
            RawdataProducer producer = client.producer(unwritten);
            RawdataMessage message = RawdataMessage.builder().position("b").build();
            RawdataException refused = assertThrows(RawdataException.class, () -> producer.publish(message));
            assertTrue(refused.getMessage().contains("GRANT USAGE, CREATE ON SCHEMA " + schema), refused.getMessage());
        }
        assertEquals(0, count("SELECT count(*) FROM " + schema + ".rawdata_topic WHERE topic <> '" + topic + "'"),
                "reading and the refused publish created nothing");
    }

    /**
     * A schema that does not exist fails the start; a name that is not a plain identifier is refused before connecting.
     */
    @Test
    void theSchemaMustExistAndBeAPlainName() {
        Map<String, String> missing = new HashMap<>(Postgres18.configuration());
        missing.put("postgres.schema", "no_such_schema");
        RawdataException failed = assertThrows(RawdataException.class, () -> RawdataClient.create(missing));
        assertTrue(failed.getMessage().contains("no_such_schema"), failed.getMessage());
        for (String name : new String[]{"Public", "1st", "a-b", "x\"; DROP TABLE y; --", "s".repeat(64)}) {
            Map<String, String> invalid = new HashMap<>(Postgres18.configuration());
            invalid.put("postgres.schema", name);
            assertThrows(IllegalArgumentException.class, () -> RawdataClient.create(invalid), name);
        }
    }

    /**
     * The same topic in two schemas is two topics: the schemas keep their data apart.
     */
    @Test
    void schemasKeepTheirTopicsApart() throws Exception {
        Map<String, String> first = Postgres18.configurationInNewSchema();
        Map<String, String> second = Postgres18.configurationInNewSchema();
        String topic = topic();
        try (RawdataClient one = RawdataClient.create(first); RawdataClient two = RawdataClient.create(second)) {
            one.producer(topic).publish(RawdataMessage.builder().position("one").build());
            two.producer(topic).publish(RawdataMessage.builder().position("two").build());
            two.metadata(topic).put("k", new byte[]{2});
            assertEquals("one", one.lastMessage(topic).position());
            assertEquals("two", two.lastMessage(topic).position());
            assertEquals(Set.of(), one.metadata(topic).keys());
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = Postgres18.connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Eight clients starting at once on an empty schema create its registry once, and eight publishing to a new topic at
     * once create it once: one registry row and one set of tables, with every message in it.
     */
    @Test
    void clientsStartingAndCreatingATopicTogetherCreateItOnce() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        String topic = topic();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch started = new CountDownLatch(8);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String position = "p" + i;
                futures.add(executor.submit(() -> {
                    try (RawdataClient client = RawdataClient.create(configuration)) {
                        started.countDown();
                        started.await();
                        client.producer(topic).publish(RawdataMessage.builder().position(position).build());
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    // several clients write the topic: all but one may find another writer, which is the contract
                    assertInstanceOf(RawdataWriterConflictException.class, e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("SELECT count(*) FROM " + schema + ".rawdata_topic"));
        assertEquals(List.of("rawdata_1_content", "rawdata_1_message", "rawdata_1_metadata", "rawdata_probe", "rawdata_topic"),
                tables(schema));
        assertTrue(count("SELECT count(*) FROM " + table(schema, topic, "message")) >= 1);
    }

    private static List<String> tables(String schema) throws Exception {
        try (Connection connection = Postgres18.connect();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name")) {
            select.setString(1, schema);
            List<String> tables = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            return tables;
        }
    }

    /**
     * A topic is created in one transaction: when creating a table fails, the registry row and the tables created
     * before it are gone too, and the next attempt creates the topic under a new number.
     */
    @Test
    void aFailedCreationLeavesNothingBehind() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        execute("CREATE TABLE " + schema + ".rawdata_1_content (blocker int)"); // in the way of topic 1's content table
        String topic = topic();
        try (RawdataClient client = RawdataClient.create(configuration); RawdataProducer producer = client.producer(topic)) {
            RawdataMessage message = RawdataMessage.builder().position("a").build();
            RawdataException failed = assertThrows(RawdataException.class, () -> producer.publish(message));
            assertTrue(failed.getMessage().contains("create topic " + topic), failed.getMessage());
            assertEquals(0, count("SELECT count(*) FROM " + schema + ".rawdata_topic"));
            assertEquals(List.of("rawdata_1_content", "rawdata_probe", "rawdata_topic"), tables(schema));
            producer.publish(message);
            assertEquals("a", client.lastMessage(topic).position());
            assertEquals(2, number(schema, topic));
        }
    }

    /**
     * A seek by position goes through the index on the position's hash, which PostgreSQL computes; the stored position
     * is the published one, byte for byte, and a long one, which no btree entry holds, is found too.
     */
    @Test
    void seekUsesThePositionHashAndThePositionIsStoredRaw() throws Exception {
        String topic = topic();
        String position = "Ærø/§1 " + "x".repeat(10_000);
        Map<String, String> configuration = new HashMap<>(Postgres18.configuration());
        configuration.put("rawdata.limits.max-position-bytes", "20000");
        long before = System.currentTimeMillis();
        try (RawdataClient client = RawdataClient.create(configuration); RawdataProducer producer = client.producer(topic)) {
            producer.publish(RawdataMessage.builder().position("other").build(), RawdataMessage.builder().position(position).build());
            assertNotNull(client.cursorOf(topic, position, true, before, java.time.Duration.ofMinutes(5)));
        }
        String message = table(topic, "message");
        try (Connection connection = Postgres18.connect(); Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            try (PreparedStatement select = connection.prepareStatement("SELECT position, position_hash = hashtextextended(position, 0) FROM "
                    + message + " WHERE position <> 'other'")) {
                try (ResultSet rows = select.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(position, rows.getString(1));
                    assertTrue(rows.getBoolean(2));
                }
            }
            try (PreparedStatement explain = connection.prepareStatement("EXPLAIN SELECT id FROM " + message
                    + " WHERE position_hash = hashtextextended(?::text COLLATE \"C\", 0) AND position = ? AND id >= ? AND id < ? ORDER BY id LIMIT 1")) {
                explain.setString(1, position);
                explain.setString(2, position);
                explain.setObject(3, RawdataIds.beginningOfTime());
                explain.setObject(4, RawdataIds.beginningOf(Long.MAX_VALUE));
                StringBuilder plan = new StringBuilder();
                try (ResultSet rows = explain.executeQuery()) {
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append('\n');
                    }
                }
                assertTrue(plan.toString().contains("rawdata_" + number("public", topic) + "_position"), plan.toString());
            }
        }
    }

    /**
     * When the server hashes the probe's word differently from when it was stored (as a changed hashtextextended
     * would after pg_upgrade), the client refuses to start, naming the statements that recompute the hashes.
     */
    @Test
    void aChangedPositionHashStopsTheClientFromStarting() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        RawdataClient.create(configuration).close(); // the probe exists
        execute("ALTER TABLE " + schema + ".rawdata_probe ALTER COLUMN hash DROP EXPRESSION");
        execute("UPDATE " + schema + ".rawdata_probe SET hash = hash + 1"); // as if stored by another hash function
        RawdataException refused = assertThrows(RawdataException.class, () -> RawdataClient.create(configuration));
        assertTrue(refused.getMessage().contains("SET position = position"), refused.getMessage());
    }

    @Test
    void aFailedPublishWritesNothing() throws Exception {
        String topic = topic();
        try (RawdataClient client = client(); RawdataProducer producer = client.producer(topic)) {
            producer.publish(RawdataMessage.builder().position("a").build());
            UUID last = client.lastMessage(topic).id();
            RawdataMessage fine = RawdataMessage.builder().position("b").put("k", new byte[]{1}).build();
            RawdataMessage duplicate = RawdataMessage.builder().id(last).position("duplicate").build();
            assertThrows(RawdataException.class, () -> producer.publish(fine, duplicate), "another message under a stored id is refused");
            assertEquals(1, count("SELECT count(*) FROM " + table(topic, "message")));
            assertEquals(0, count("SELECT count(*) FROM " + table(topic, "content")));
        }
    }

    /**
     * A message published again adds no rows, its content neither; the contract (the TCK) checks what a reader sees.
     *
     * @throws Exception if the database fails
     */
    @Test
    void aRepeatedMessageAddsNoRows() throws Exception {
        String topic = topic();
        UUID id = RawdataIds.next(RawdataIds.beginningOfTime());
        RawdataMessage message = RawdataMessage.builder().id(id).position("tx").orderingGroup("g").sequenceNumber(7)
                .put("a", new byte[]{1, 2}).put("b", new byte[]{3}).build();
        try (RawdataClient client = client(); RawdataProducer producer = client.producer(topic)) {
            producer.publish(message);
            producer.publish(message, RawdataMessage.builder().position("next").build(), message);
        }
        assertEquals(2, count("SELECT count(*) FROM " + table(topic, "message")), "tx and next");
        assertEquals(2, count("SELECT count(*) FROM " + table(topic, "content")));
    }

    /**
     * A topic's tables are named by its number in the registry, never by the topic, which is only ever a bind
     * parameter: a name that reads as SQL is just a name.
     */
    @Test
    void topicTablesAreNamedByNumberNeverByTopic() throws Exception {
        Map<String, String> configuration = Postgres18.configurationInNewSchema();
        String schema = configuration.get("postgres.schema");
        try (RawdataClient client = RawdataClient.create(configuration);
             RawdataProducer producer = client.producer("Robert.DROP-TABLE_students")) {
            producer.publish(RawdataMessage.builder().position("a").build());
        }
        assertEquals(List.of("rawdata_1_content", "rawdata_1_message", "rawdata_1_metadata", "rawdata_probe", "rawdata_topic"),
                tables(schema));
        assertEquals(1, number(schema, "Robert.DROP-TABLE_students"));
    }

    @Test
    void missingConfigurationIsReported() {
        Map<String, String> configuration = new HashMap<>(Postgres18.configuration());
        configuration.remove("postgres.driver.user");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RawdataClient.create(configuration));
        assertTrue(e.getMessage().contains("postgres.driver.user"));
        configuration.put("postgres.driver.user", "rdc");
        configuration.put("rawdata.postgres.consumer.prefetch-size", "0");
        assertThrows(IllegalArgumentException.class, () -> RawdataClient.create(configuration));
    }

    @Test
    void closingTheClientClosesItsPool() throws Exception {
        Set<Long> before = backends();
        RawdataClient client = client();
        client.lastMessage(topic());
        Set<Long> own = backends();
        own.removeAll(before); // this client's connections, whatever other clients do meanwhile
        assertFalse(own.isEmpty(), "the client holds connections");
        client.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Set<Long> left = backends();
        left.retainAll(own);
        while (!left.isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "closing the client closes its connections: " + left);
            left = backends();
            left.retainAll(own);
        }
        assertTrue(client.isClosed());
    }

    private static Set<Long> backends() throws Exception {
        Set<Long> pids = new HashSet<>();
        try (Connection connection = Postgres18.connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT pid FROM pg_stat_activity WHERE application_name = 'rawdata-postgres'")) {
            while (rows.next()) {
                pids.add(rows.getLong(1));
            }
        }
        return pids;
    }
}
