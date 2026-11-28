package io.descoped.rawdata.api.test;

import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataCopy;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataProducer;
import io.descoped.rawdata.api.spi.RawdataLimits;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawdataCopyTest {

    private static RawdataClient memory() {
        return RawdataClient.create(Map.of(RawdataClient.PROVIDER_KEY, "memory"));
    }

    private static void fill(RawdataClient client, String topic, int count) {
        try (RawdataProducer producer = client.producer(topic)) {
            for (int i = 0; i < count; i++) {
                producer.publish(RawdataMessage.builder().position("p" + i).put("v", ("value " + i).getBytes(StandardCharsets.UTF_8)).build());
            }
        }
    }

    private static List<RawdataMessage> readAll(RawdataClient client, String topic) throws InterruptedException {
        List<RawdataMessage> messages = new ArrayList<>();
        try (RawdataConsumer consumer = client.consumer(topic)) {
            RawdataMessage message;
            while ((message = consumer.receive(0, TimeUnit.SECONDS)) != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Test
    void copiesATopicBetweenClientsKeepingIdsAndOrder() throws Exception {
        try (RawdataClient source = memory(); RawdataClient target = memory()) {
            fill(source, "t", 1000);
            try (RawdataConsumer from = source.consumer("t"); RawdataProducer to = target.producer("t")) {
                assertEquals(1000, RawdataCopy.copy(from, to, 64, Duration.ZERO));
            }
            assertEquals(readAll(source, "t"), readAll(target, "t"));
        }
    }

    @Test
    void splitsABatchSoEachCallFitsTheTargetsPublishLimit() throws Exception {
        Map<String, String> small = Map.of(RawdataClient.PROVIDER_KEY, "memory",
                RawdataLimits.MAX_POSITION_BYTES, "100", RawdataLimits.MAX_PUBLISH_BYTES, "1000");
        try (RawdataClient source = memory(); RawdataClient target = RawdataClient.create(small)) {
            try (RawdataProducer producer = source.producer("t")) {
                for (int i = 0; i < 10; i++) {
                    producer.publish(RawdataMessage.builder().position("p" + i).put("v", new byte[300]).build());
                }
            }
            List<Integer> batches = new ArrayList<>();
            RawdataProducer counting = new ForwardingProducer(target.producer("t")) {
                @Override
                public void publish(RawdataMessage... messages) {
                    batches.add(messages.length);
                    super.publish(messages);
                }
            };
            RawdataLimits limits = RawdataLimits.of(small);
            try (RawdataConsumer from = source.consumer("t")) {
                assertEquals(10, RawdataCopy.copy(from, counting, 1000, limits, Duration.ZERO));
            }
            assertEquals(List.of(2, 2, 2, 2, 2), batches, "each message counts 345 bytes, so two fit in 1000");
            assertEquals(readAll(source, "t"), readAll(target, "t"));
        }
    }

    @Test
    void aMessageTooLargeForTheTargetFailsTheCopy() throws Exception {
        Map<String, String> small = Map.of(RawdataClient.PROVIDER_KEY, "memory",
                RawdataLimits.MAX_POSITION_BYTES, "100", RawdataLimits.MAX_PUBLISH_BYTES, "1000");
        try (RawdataClient source = memory(); RawdataClient target = RawdataClient.create(small)) {
            fill(source, "t", 1);
            try (RawdataProducer producer = source.producer("t")) {
                producer.publish(RawdataMessage.builder().position("big").put("v", new byte[2000]).build());
            }
            RawdataLimits limits = RawdataLimits.of(small);
            try (RawdataConsumer from = source.consumer("t"); RawdataProducer to = target.producer("t")) {
                assertThrows(IllegalArgumentException.class, () -> RawdataCopy.copy(from, to, 1, limits, Duration.ZERO));
            }
            assertEquals(1, readAll(target, "t").size(), "the message before it is copied");
        }
    }

    @Test
    void publishesInBatchesOfTheChosenSize() throws Exception {
        try (RawdataClient source = memory(); RawdataClient target = memory()) {
            fill(source, "t", 25);
            List<Integer> batches = new ArrayList<>();
            RawdataProducer real = target.producer("t");
            RawdataProducer counting = new ForwardingProducer(real) {
                @Override
                public void publish(RawdataMessage... messages) {
                    batches.add(messages.length);
                    super.publish(messages);
                }
            };
            try (RawdataConsumer from = source.consumer("t")) {
                assertEquals(25, RawdataCopy.copy(from, counting, 10, Duration.ZERO));
            }
            assertEquals(List.of(10, 10, 5), batches);
        }
    }

    @Test
    void waitsForMessagesUntilTheIdleTimeout() throws Exception {
        try (RawdataClient source = memory(); RawdataClient target = memory()) {
            fill(source, "t", 3);
            CountDownLatch waiting = new CountDownLatch(1);
            CompletableFuture<Void> late = CompletableFuture.runAsync(() -> {
                try {
                    if (waiting.await(30, TimeUnit.SECONDS)) {
                        fill(source, "t", 2); // once the copy waits for more
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            try (RawdataConsumer from = new WaitSignallingConsumer(source.consumer("t"), waiting);
                 RawdataProducer to = target.producer("t")) {
                assertEquals(5, RawdataCopy.copy(from, to, 100, Duration.ofSeconds(5)));
            }
            late.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void aFailingPublishStopsTheCopy() throws Exception {
        try (RawdataClient source = memory(); RawdataClient target = memory()) {
            fill(source, "t", 50);
            AtomicInteger calls = new AtomicInteger();
            RawdataProducer failing = new ForwardingProducer(target.producer("t")) {
                @Override
                public void publish(RawdataMessage... messages) {
                    if (calls.incrementAndGet() == 3) {
                        throw new IllegalStateException("store down");
                    }
                    super.publish(messages);
                }
            };
            try (RawdataConsumer from = source.consumer("t")) {
                IllegalStateException e = assertThrows(IllegalStateException.class, () -> RawdataCopy.copy(from, failing, 10, Duration.ZERO));
                assertEquals("store down", e.getMessage());
            }
            assertEquals(20, readAll(target, "t").size());
        }
    }

    @Test
    void invalidArgumentsAreRefused() {
        try (RawdataClient client = memory(); RawdataConsumer from = client.consumer("t"); RawdataProducer to = client.producer("u")) {
            assertThrows(IllegalArgumentException.class, () -> RawdataCopy.copy(from, to, 0, Duration.ZERO));
            Duration negative = Duration.ofSeconds(-1);
            assertThrows(IllegalArgumentException.class, () -> RawdataCopy.copy(from, to, 1, negative));
            assertThrows(NullPointerException.class, () -> RawdataCopy.copy(null, to, 1, Duration.ZERO));
            assertThrows(NullPointerException.class, () -> RawdataCopy.copy(from, null, 1, Duration.ZERO));
        }
    }

    @Test
    void receiveUpToTakesWhatIsAvailable() throws Exception {
        try (RawdataClient client = memory()) {
            fill(client, "t", 7);
            try (RawdataConsumer consumer = client.consumer("t")) {
                assertEquals(5, consumer.receiveUpTo(5, Duration.ZERO).size());
                List<RawdataMessage> rest = consumer.receiveUpTo(5, Duration.ZERO);
                assertEquals(List.of("p5", "p6"), rest.stream().map(RawdataMessage::position).toList());
                long start = System.nanoTime();
                assertTrue(consumer.receiveUpTo(5, Duration.ofMillis(100)).isEmpty());
                assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(90));
            }
        }
    }

    private static class ForwardingProducer implements RawdataProducer {
        private final RawdataProducer delegate;

        ForwardingProducer(RawdataProducer delegate) {
            this.delegate = delegate;
        }

        @Override
        public String topic() {
            return delegate.topic();
        }

        @Override
        public void publish(RawdataMessage... messages) {
            delegate.publish(messages);
        }

        @Override
        public CompletableFuture<Void> publishAsync(RawdataMessage... messages) {
            return delegate.publishAsync(messages);
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * A consumer that counts a latch down when it is asked to wait for a message the second time: the copy has taken
     * the first batch and waits for more.
     */
    private static final class WaitSignallingConsumer implements RawdataConsumer {

        private final RawdataConsumer consumer;
        private final CountDownLatch waiting;
        private final AtomicInteger waits = new AtomicInteger();

        WaitSignallingConsumer(RawdataConsumer consumer, CountDownLatch waiting) {
            this.consumer = consumer;
            this.waiting = waiting;
        }

        @Override
        public String topic() {
            return consumer.topic();
        }

        @Override
        public RawdataMessage receive(int timeout, TimeUnit unit) throws InterruptedException {
            if (timeout > 0 && waits.incrementAndGet() == 2) {
                waiting.countDown();
            }
            return consumer.receive(timeout, unit);
        }

        @Override
        public CompletableFuture<? extends RawdataMessage> receiveAsync() {
            return consumer.receiveAsync();
        }

        @Override
        public void seek(long timestamp) {
            consumer.seek(timestamp);
        }

        @Override
        public boolean isClosed() {
            return consumer.isClosed();
        }

        @Override
        public void close() {
            consumer.close();
        }
    }
}
