package io.descoped.rawdata.provider.filesystem.test;

import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Publish calls per second with one message per call: one thread, several threads on one producer, and one thread
 * with {@code publishAsync} calls in flight. Not a test: run it on the disk to measure with
 * {@code -Drawdata.bench=true -Dtest=FilesystemPublishBenchmarkTest}; it prints its results.
 */
class FilesystemPublishBenchmarkTest {

    private static final int SECONDS = 5;

    @TempDir
    Path folder;

    @Test
    void publishCallsPerSecond() throws Exception {
        assumeTrue(Boolean.getBoolean("rawdata.bench"), "-Drawdata.bench=true runs the benchmark");
        System.out.printf("publish calls per second, one 1 KiB message per call, %d s each%n", SECONDS);
        for (int threads : new int[]{1, 4, 16, 64}) {
            double rate = threads(threads);
            assertTrue(rate > 0, "every run publishes");
            System.out.printf("  %2d threads               %8.0f%n", threads, rate);
        }
        double async = async(64);
        assertTrue(async > 0, "every run publishes");
        System.out.printf("  publishAsync, 64 in flight %6.0f%n", async);
    }

    private RawdataClient client() {
        return RawdataClient.create(Map.of(
                RawdataClient.PROVIDER_KEY, "filesystem",
                "filesystem.storage-folder", folder.resolve("topics").toString(),
                "local-temp-folder", folder.resolve("buffer-" + UUID.randomUUID()).toString(),
                "avro-file.max.seconds", "3600"));
    }

    private static RawdataMessage message(long n) {
        return RawdataMessage.builder().position(Long.toString(n)).put("payload", new byte[1024]).build();
    }

    private double threads(int threads) throws Exception {
        try (RawdataClient client = client(); RawdataProducer producer = client.producer("bench")) {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Long>> counts = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    counts.add(executor.submit(() -> {
                        long calls = 0;
                        while (System.nanoTime() < end) {
                            producer.publish(message(calls++));
                        }
                        return calls;
                    }));
                }
                long calls = 0;
                for (Future<Long> count : counts) {
                    calls += count.get();
                }
                return calls / (double) SECONDS;
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private double async(int inFlight) throws Exception {
        try (RawdataClient client = client(); RawdataProducer producer = client.producer("bench")) {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
            List<CompletableFuture<Void>> window = new ArrayList<>();
            long calls = 0;
            while (System.nanoTime() < end) {
                window.add(producer.publishAsync(message(calls++)));
                if (window.size() == inFlight) {
                    window.remove(0).get();
                }
            }
            for (CompletableFuture<Void> future : window) {
                future.get();
            }
            return calls / (double) SECONDS;
        }
    }
}
