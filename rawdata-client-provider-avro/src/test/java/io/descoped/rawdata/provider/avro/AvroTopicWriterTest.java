package io.descoped.rawdata.provider.avro;

import io.descoped.rawdata.api.RawdataDuplicateIdException;
import io.descoped.rawdata.api.RawdataException;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataProducer;
import io.descoped.rawdata.api.RawdataWriterConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upload window's failure paths, which a real store cannot be made to hit on cue: a store whose uploads wait for
 * the test, then fail or find their segment taken.
 */
@Timeout(60)
class AvroTopicWriterTest {

    @TempDir
    Path folder;

    /**
     * A store in memory whose uploads can be held back, failed or refused.
     */
    static final class GatedStore implements AvroStore {

        enum Outcome { CREATE, FAIL, TAKEN }

        final Map<String, byte[]> segments = new ConcurrentHashMap<>();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch uploading = new CountDownLatch(1);
        final CountDownLatch stored = new CountDownLatch(1); // a segment was put
        volatile Outcome outcome = Outcome.CREATE;
        volatile boolean gated;
        volatile boolean gatedAfterCreate; // the segment is stored, the upload not yet done
        volatile String slowTopic; // looking up this topic's segments waits for slowRelease
        final CountDownLatch slowEntered = new CountDownLatch(1);
        final CountDownLatch slowRelease = new CountDownLatch(1);

        @Override
        public boolean create(String topic, long segment, byte[] content) throws IOException {
            uploading.countDown();
            if (gated) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            switch (outcome) {
                case FAIL:
                    throw new IOException("the store is down");
                case TAKEN:
                    return false;
                default:
                    byte[] before = segments.putIfAbsent(topic + "/" + segment, content.clone());
                    stored.countDown();
                    if (gatedAfterCreate) {
                        await(release);
                    }
                    return before == null || Arrays.equals(before, content);
            }
        }

        private static void await(CountDownLatch latch) throws IOException {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        @Override
        public Optional<Segment> open(String topic, long segment) {
            byte[] content = segments.get(topic + "/" + segment);
            if (content == null) {
                return Optional.empty();
            }
            return Optional.of(new Segment() {
                @Override
                public long size() {
                    return content.length;
                }

                @Override
                public byte[] read(long offset, int length) {
                    return Arrays.copyOfRange(content, (int) offset, (int) Math.min(content.length, offset + length));
                }
            });
        }

        @Override
        public boolean exists(String topic, long segment) throws IOException {
            if (topic.equals(slowTopic)) {
                slowEntered.countDown();
                await(slowRelease);
            }
            return segments.containsKey(topic + "/" + segment);
        }

        @Override
        public Set<String> metadataNames(String topic) {
            return new TreeSet<>();
        }

        @Override
        public Optional<byte[]> readMetadata(String topic, String name) {
            return Optional.empty();
        }

        @Override
        public void writeMetadata(String topic, String name, byte[] value) {
            // not used
        }

        @Override
        public void deleteMetadata(String topic, String name) {
            // not used
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    private AvroRawdataClient client(GatedStore store, long maxBytes, int maxPending) {
        return new AvroRawdataClient(store, new AvroUploadWindow(folder, 3600, maxBytes, 64, maxPending, 30, 16), 10);
    }

    private static RawdataMessage message(String position, int size) {
        return RawdataMessage.builder().position(position).put("payload", new byte[size]).build();
    }

    private List<String> leftoverPositions() throws IOException {
        List<String> windows = files();
        assertEquals(1, windows.size(), "one open window: " + windows);
        AvroSegments.Leftover leftover = AvroSegments.readLeftover(folder.resolve("t").resolve(windows.get(0)));
        return leftover.messages().stream().map(RawdataMessage::position).toList();
    }

    private List<String> files() throws IOException {
        try (Stream<Path> files = Files.list(folder.resolve("t"))) {
            return files.map(file -> file.getFileName().toString()).filter(name -> !name.equals(".lock")).sorted().toList();
        }
    }

    /**
     * A message with a generated id is seen once its segment is stored, while the writer still counts the window as
     * waiting for upload. Published again then, it is a no-op; a different message under its id is refused.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void aGeneratedIdSeenWhileItsUploadFinishesIsComparedWithTheStoredMessage() throws Exception {
        GatedStore store = new GatedStore();
        store.gatedAfterCreate = true;
        try (AvroRawdataClient client = client(store, 4000, 4)) {
            RawdataProducer producer = client.producer("t");
            producer.publish(message("large", 5000)); // over the size: sealed, uploaded, the upload not yet done
            assertTrue(store.stored.await(10, TimeUnit.SECONDS));
            RawdataMessage seen = client.lastMessage("t");
            assertEquals("large", seen.position());
            producer.publish(seen);
            RawdataMessage other = RawdataMessage.builder().id(seen.id()).position("other").build();
            assertThrows(RawdataDuplicateIdException.class, () -> producer.publish(other));
            store.release.countDown();
            client.flush("t");
            assertEquals(1, store.segments.size(), "nothing more was written");
        }
    }

    /**
     * An upload that fails while a newer window is open fails the writer; closing throws and keeps both windows on
     * disk, sealed, for the next writer.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void aFailedUploadKeepsTheOpenWindowSealedToo() throws Exception {
        GatedStore store = new GatedStore();
        store.gated = true;
        store.outcome = GatedStore.Outcome.FAIL;
        AvroRawdataClient client = client(store, 4000, 4); // above the Avro header
        RawdataProducer producer = client.producer("t");
        producer.publish(message("large", 5000)); // over the size: sealed, its upload waits
        assertTrue(store.uploading.await(10, TimeUnit.SECONDS));
        producer.publish(message("small", 1)); // a new window, open
        store.release.countDown();
        assertThrows(RawdataException.class, client::close);
        assertEquals(List.of("0000000000000000000.sealed.avro", "0000000000000000001.sealed.avro"), files());
    }

    /**
     * An upload that finds its segment taken is another writer: the windows not uploaded, the open one too, are set
     * aside, and closing reports the conflict.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void aTakenSegmentSetsAsideEveryWindow() throws Exception {
        GatedStore store = new GatedStore();
        store.gated = true;
        store.outcome = GatedStore.Outcome.TAKEN;
        AvroRawdataClient client = client(store, 4000, 4); // above the Avro header
        RawdataProducer producer = client.producer("t");
        producer.publish(message("large", 5000));
        assertTrue(store.uploading.await(10, TimeUnit.SECONDS));
        producer.publish(message("small", 1)); // a new window, open
        store.release.countDown();
        assertThrows(RawdataWriterConflictException.class, client::close);
        // The open window is set aside open or sealed, as the close and the failed upload happen to meet.
        List<String> files = files();
        assertEquals(2, files.size(), files.toString());
        assertTrue(files.get(0).startsWith("0000000000000000000.") && files.get(1).startsWith("0000000000000000001."), files.toString());
        assertTrue(files.stream().allMatch(name -> name.endsWith(".avro.invalid")), files.toString());
    }

    /**
     * When as many sealed windows wait for upload as {@code avro-file.max.pending-uploads} allows, a publish waits
     * until one is uploaded.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void aPublishWaitsWhileTooManyWindowsWaitForUpload() throws Exception {
        GatedStore store = new GatedStore();
        store.gated = true;
        try (AvroRawdataClient client = client(store, 1, 1)) {
            RawdataProducer producer = client.producer("t");
            producer.publish(message("a", 1)); // sealed; its upload waits
            assertTrue(store.uploading.await(10, TimeUnit.SECONDS));
            Thread second = new Thread(() -> producer.publish(message("b", 1)));
            second.start();
            second.join(300);
            assertTrue(second.isAlive(), "waits for the upload before it");
            store.release.countDown();
            second.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(second.isAlive());
            client.flush("t");
            assertEquals(2, store.segments.size());
        }
    }

    /**
     * Closing a producer waits at most {@code avro-file.close-timeout-seconds} for the uploads; the uploads go on, and
     * the client can still publish. Closing the client waits as long, then stops; the window stays on disk and the
     * next writer uploads it.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void closingWaitsAtMostTheCloseTimeout() throws Exception {
        GatedStore store = new GatedStore();
        store.gated = true;
        AvroRawdataClient client = new AvroRawdataClient(store, new AvroUploadWindow(folder, 3600, 1 << 20, 64, 4, 1, 16), 10);
        RawdataProducer producer = client.producer("t");
        producer.publish(message("a", 1));
        long started = System.nanoTime();
        RawdataException timedOut = assertThrows(RawdataException.class, producer::close);
        assertTrue(timedOut.getMessage().contains("timed out"), timedOut.getMessage());
        client.producer("t").publish(message("b", 1)); // the writer goes on

        RawdataException closing = assertThrows(RawdataException.class, client::close);
        assertTrue(closing.getMessage().contains("timed out"), closing.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10, "bounded by the timeout");
        store.release.countDown();
        List<String> left = files();
        assertTrue(left.contains("0000000000000000001.sealed.avro"), left.toString()); // never uploaded

        GatedStore after = new GatedStore();
        after.segments.putAll(store.segments);
        try (AvroRawdataClient next = client(after, 1 << 20, 4)) {
            next.producer("t").publish(message("c", 1));
            next.flush("t");
        }
        assertEquals(List.of(), files(), "the next writer uploaded what was left");
    }

    /**
     * Leftovers that hold no message (a header cut short, a file without the window's header) are removed, and the
     * writer starts at the topic's end.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void leftoversWithoutMessagesAreRemoved() throws Exception {
        Path topic = Files.createDirectories(folder.resolve("t"));
        Files.write(topic.resolve("0000000000000000000.window.avro"), new byte[]{'O', 'b', 'j'});
        byte[] noHeader = AvroSegments.encode(List.of(), 1, io.descoped.rawdata.api.RawdataIds.beginningOfTime(), 64);
        Files.write(topic.resolve("0000000000000000001.sealed.avro"), noHeader);
        GatedStore store = new GatedStore();
        try (AvroRawdataClient client = client(store, 1 << 20, 4)) {
            client.producer("t").publish(message("a", 1));
            client.flush("t");
        }
        assertEquals(List.of(), files());
        assertEquals(Set.of("t/0"), store.segments.keySet());
    }

    /**
     * Every call that returned is in the window file, written whole, also the calls since the file was last forced to
     * disk: what a crash of the process leaves for the next writer. Calls larger and smaller than the sync interval.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void everyReturnedCallIsInTheWindowFileWithoutASyncPerCall() throws Exception {
        GatedStore store = new GatedStore();
        try (AvroRawdataClient client = new AvroRawdataClient(store,
                new AvroUploadWindow(folder, 3600, 1L << 30, 256, 4, 30, 16), 10)) {
            RawdataProducer producer = client.producer("t");
            List<String> published = new ArrayList<>();
            for (int call = 0; call < 7; call++) {
                List<RawdataMessage> batch = new ArrayList<>();
                int size = call % 2 == 0 ? 10 : 1; // ten messages of 100 bytes pass 256 and force; one does not
                for (int i = 0; i < size; i++) {
                    batch.add(message("c" + call + "-" + i, 100));
                    published.add("c" + call + "-" + i);
                }
                producer.publish(batch);
                assertEquals(published, leftoverPositions(), "call " + call + " is in the file when it returns");
            }
        }
    }

    /**
     * A thread with its interrupt flag set publishes, forces the file and seals windows like any other: the window
     * file is never written through an NIO channel, which an interrupted thread closes. The flag is kept, and the
     * writer goes on.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void anInterruptedPublisherWritesForcesAndSealsAndTheWriterGoesOn() throws Exception {
        GatedStore store = new GatedStore();
        try (AvroRawdataClient client = new AvroRawdataClient(store,
                new AvroUploadWindow(folder, 3600, 4000, 64, 4, 30, 16), 10)) {
            RawdataProducer producer = client.producer("t");
            Thread.currentThread().interrupt();
            try {
                producer.publish(message("forced", 100)); // past the sync interval: the file is forced
                producer.publish(message("sealed", 5000)); // past the size: the window is sealed
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
            } finally {
                Thread.interrupted();
            }
            producer.publish(message("after", 10));
            client.flush("t");
            assertEquals("after", client.lastMessage("t").position());
            assertEquals(Set.of("t/0", "t/1"), store.segments.keySet());
        }
    }

    /**
     * A thread with its interrupt flag set gets a producer like any other: the writer takes the folder's lock and
     * uploads what a crash left. The flag is kept.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void anInterruptedThreadStartsTheWriterAndRecovers() throws Exception {
        Path topic = Files.createDirectories(folder.resolve("t"));
        RawdataMessage left = RawdataMessage.builder().id(io.descoped.rawdata.api.RawdataIds.generate()).position("left").build();
        Files.write(topic.resolve("0000000000000000000.sealed.avro"),
                AvroSegments.encode(List.of(left), 0, io.descoped.rawdata.api.RawdataIds.beginningOfTime(), 64));
        GatedStore store = new GatedStore();
        try (AvroRawdataClient client = client(store, 1 << 20, 4)) {
            RawdataProducer producer;
            Thread.currentThread().interrupt();
            try {
                producer = client.producer("t");
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
            } finally {
                Thread.interrupted();
            }
            assertEquals("left", client.lastMessage("t").position(), "recovered");
            producer.publish(message("after", 1));
            client.flush("t");
            assertEquals("after", client.lastMessage("t").position());
            assertEquals(Set.of("t/0", "t/1"), store.segments.keySet());
        }
        assertEquals(List.of(), files());
    }

    /**
     * Starting a topic's writer reads the store and may upload what a crash left: a slow store there holds up only
     * that topic, never the producers of other topics, also one whose writer shares the slot of the client's map of
     * writers.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void aSlowWriterStartHoldsUpOnlyItsOwnTopic() throws Exception {
        GatedStore store = new GatedStore();
        String other = sharesTheMapSlotWith("slow");
        store.slowTopic = "slow";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (AvroRawdataClient client = client(store, 1 << 20, 4)) {
            Future<RawdataProducer> slow = executor.submit(() -> client.producer("slow"));
            try {
                assertTrue(store.slowEntered.await(10, TimeUnit.SECONDS));
                Future<?> otherPublished = executor.submit(() -> {
                    client.producer(other).publish(message("a", 1));
                    return null;
                });
                otherPublished.get(10, TimeUnit.SECONDS); // done while the slow topic's writer starts
                assertFalse(slow.isDone());
            } finally {
                store.slowRelease.countDown(); // before the client closes, also when the test fails
            }
            slow.get(10, TimeUnit.SECONDS).publish(message("b", 1));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A topic that a small {@link ConcurrentHashMap} puts in the same slot as the given one.
     */
    private static String sharesTheMapSlotWith(String topic) {
        for (int i = 0; ; i++) {
            String candidate = "t" + i;
            if (slot(candidate) == slot(topic)) {
                return candidate;
            }
        }
    }

    private static int slot(String key) {
        int h = key.hashCode();
        return (h ^ (h >>> 16)) & 15; // ConcurrentHashMap's spread, in its first table of 16
    }
}
