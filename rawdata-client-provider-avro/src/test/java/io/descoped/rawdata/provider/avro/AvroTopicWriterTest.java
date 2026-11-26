package io.descoped.rawdata.provider.avro;

import io.descoped.rawdata.api.RawdataDuplicateIdException;
import io.descoped.rawdata.api.RawdataException;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataConsumer;
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
        public boolean exists(String topic, long segment) {
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
    /**
     * Group commit: calls of several threads share blocks and disk syncs, and once every call returned, the open window
     * on disk, read as recovery reads it, holds every message once.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void concurrentPublishesThatReturnedAreAllOnDisk() throws Exception {
        GatedStore store = new GatedStore();
        AvroUploadWindow window = new AvroUploadWindow(folder, 3600, 1L << 30, 512 * 1024, 4, 30, 16);
        int threads = 8;
        int calls = 200;
        try (AvroRawdataClient client = new AvroRawdataClient(store, window, 10)) {
            RawdataProducer producer = client.producer("t");
            runConcurrently(threads, thread -> {
                for (int c = 0; c < calls; c++) {
                    producer.publish(message(thread + "-" + c, 100)); // blocks far below the sync interval
                }
            });
            List<String> positions = leftoverPositions();
            assertEquals(threads * calls, positions.size(), "every returned call is on disk");
            assertEquals(threads * calls, new TreeSet<>(positions).size(), "each once");
        }
    }

    /**
     * Group commit while windows seal on size under the callers: a window is sealed and renamed while another caller
     * forces it. Every message is stored once, and each thread's calls keep their order.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void concurrentPublishesAcrossSealedWindowsAreEachStoredOnceInOrder() throws Exception {
        GatedStore store = new GatedStore();
        AvroUploadWindow window = new AvroUploadWindow(folder, 3600, 2048, 512 * 1024, 4, 30, 16);
        int threads = 8;
        int calls = 100;
        try (AvroRawdataClient client = new AvroRawdataClient(store, window, 10)) {
            RawdataProducer producer = client.producer("t");
            runConcurrently(threads, thread -> {
                for (int c = 0; c < calls; c++) {
                    producer.publish(message(thread + "-" + c, 100));
                }
            });
            client.flush("t");
            assertTrue(store.segments.size() > 10, "many windows sealed: " + store.segments.size());
            List<String> read = new ArrayList<>();
            try (RawdataConsumer consumer = client.consumer("t")) {
                RawdataMessage message;
                while ((message = consumer.receive(1, TimeUnit.SECONDS)) != null) {
                    read.add(message.position());
                }
            }
            assertEquals(threads * calls, read.size(), "every call once");
            assertEquals(threads * calls, new TreeSet<>(read).size(), "no message twice");
            for (int t = 0; t < threads; t++) {
                String prefix = t + "-";
                List<Integer> order = read.stream().filter(p -> p.startsWith(prefix))
                        .map(p -> Integer.parseInt(p.substring(prefix.length()))).toList();
                assertEquals(java.util.stream.IntStream.range(0, calls).boxed().toList(), order, "thread " + t + " in order");
            }
        }
    }

    /**
     * Calls that repeat messages written by another call at the same time are no-ops that still wait for the disk:
     * once every call returned, each message is on disk once.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void concurrentRepeatsOfTheSameMessagesAreOnDiskOnce() throws Exception {
        GatedStore store = new GatedStore();
        AvroUploadWindow window = new AvroUploadWindow(folder, 3600, 1L << 30, 512 * 1024, 4, 30, 16);
        List<RawdataMessage> messages = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            messages.add(RawdataMessage.builder().id(io.descoped.rawdata.api.RawdataIds.generate()).position("m" + i)
                    .put("payload", new byte[]{(byte) i}).build());
        }
        try (AvroRawdataClient client = new AvroRawdataClient(store, window, 10)) {
            RawdataProducer producer = client.producer("t");
            runConcurrently(8, thread -> {
                for (RawdataMessage message : messages) {
                    producer.publish(message);
                }
            });
            List<String> positions = leftoverPositions();
            assertEquals(50, positions.size(), "each message once: " + positions.size());
            assertEquals(50, new TreeSet<>(positions).size());
        }
    }

    /**
     * Sealed windows whose uploads a crash cut off are uploaded, in order, when the next process creates its producer,
     * before {@code producer} returns: {@code lastMessage} holds them before any publish.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void producerUploadsTheSealedWindowsACrashLeftBeforeItReturns() throws Exception {
        GatedStore store = new GatedStore();
        store.gated = true;
        Path crashedFolder = folder.resolve("crashed");
        Path restartedFolder = folder.resolve("restarted");
        AvroRawdataClient crashed = new AvroRawdataClient(store,
                new AvroUploadWindow(crashedFolder, 3600, 1, 64, 4, 1, 16), 10);
        try {
            RawdataProducer producer = crashed.producer("t");
            for (String position : List.of("a", "b", "c")) {
                producer.publish(message(position, 10)); // each fills its window: sealed, its upload held back
            }
            assertTrue(store.uploading.await(10, TimeUnit.SECONDS));
            Files.createDirectories(restartedFolder.resolve("t"));
            try (Stream<Path> files = Files.list(crashedFolder.resolve("t"))) {
                for (Path file : files.filter(f -> !f.getFileName().toString().equals(".lock")).toList()) {
                    Files.copy(file, restartedFolder.resolve("t").resolve(file.getFileName()));
                }
            }
            store.gated = false; // the crashed process's held upload stays held; the restarted one's go through
            try (AvroRawdataClient restarted = new AvroRawdataClient(store,
                    new AvroUploadWindow(restartedFolder, 3600, 1, 64, 4, 30, 16), 10)) {
                restarted.producer("t");
                assertEquals("c", restarted.lastMessage("t").position(), "every sealed window, before any publish");
                assertEquals(Set.of("t/0", "t/1", "t/2"), store.segments.keySet());
            }
        } finally {
            store.release.countDown();
            try {
                crashed.close();
            } catch (RuntimeException e) {
                // the crashed process never closes
            }
        }
    }

    /**
     * A call larger than the sync interval ends its block as it is appended; mixed with small calls, every call that
     * returned is on disk.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void callsLargerThanTheSyncIntervalAndSmallCallsAreAllOnDisk() throws Exception {
        GatedStore store = new GatedStore();
        try (AvroRawdataClient client = new AvroRawdataClient(store,
                new AvroUploadWindow(folder, 3600, 1L << 30, 256, 4, 30, 16), 10)) {
            RawdataProducer producer = client.producer("t");
            List<String> published = new ArrayList<>();
            for (int call = 0; call < 6; call++) {
                List<RawdataMessage> batch = new ArrayList<>();
                int size = call % 2 == 0 ? 10 : 1; // ten messages of 100 bytes pass 256; one does not
                for (int i = 0; i < size; i++) {
                    batch.add(message("c" + call + "-" + i, 100));
                    published.add("c" + call + "-" + i);
                }
                producer.publish(batch);
            }
            assertEquals(published, leftoverPositions());
        }
    }

    private List<String> leftoverPositions() throws IOException {
        List<String> windows = files();
        assertEquals(1, windows.size(), "one open window: " + windows);
        AvroSegments.Leftover leftover = AvroSegments.readLeftover(folder.resolve("t").resolve(windows.get(0)));
        return leftover.messages().stream().map(RawdataMessage::position).toList();
    }

    private interface Task {
        void run(int thread) throws Exception;
    }

    private static void runConcurrently(int threads, Task task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                done.add(executor.submit(() -> {
                    start.await();
                    task.run(thread);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : done) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

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
}
