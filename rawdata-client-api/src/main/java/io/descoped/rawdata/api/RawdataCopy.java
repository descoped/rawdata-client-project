package io.descoped.rawdata.api;

import io.descoped.rawdata.api.spi.RawdataLimits;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Copies messages from a consumer to a producer, for example from a topic in one provider to a topic in another.
 * Messages keep their ids, so the copy has the same order and the same ids as the source. The copy is pulled in
 * batches of a size the caller chooses, so memory use is bounded by one batch, and each batch is published in calls
 * that fit the target's publish limit.
 * <pre>{@code
 * try (RawdataConsumer from = source.consumer("topic"); RawdataProducer to = target.producer("topic")) {
 *     long copied = RawdataCopy.copy(from, to, 500, Duration.ofSeconds(1));
 * }
 * }</pre>
 */
public final class RawdataCopy {

    private RawdataCopy() {
    }

    /**
     * Copies until the source has no message for the idle timeout, with the default limits
     * ({@link RawdataLimits#DEFAULT}) for the target; see {@link #copy(RawdataConsumer, RawdataProducer, int, RawdataLimits, Duration)}.
     *
     * @param from        the consumer to read
     * @param to          the producer to write
     * @param batchSize   the most messages per publish, 1 or more
     * @param idleTimeout how long to wait for a message before the copy is done
     * @return the number of messages copied
     * @throws IllegalArgumentException if the batch size is less than 1, the timeout is negative, or a message does not
     *                                  fit the target's limits
     * @throws InterruptedException     if the thread is interrupted while waiting
     * @throws RawdataClosedException   if the consumer or the producer is closed
     */
    public static long copy(RawdataConsumer from, RawdataProducer to, int batchSize, Duration idleTimeout) throws InterruptedException {
        return copy(from, to, batchSize, RawdataLimits.DEFAULT, idleTimeout);
    }

    /**
     * Copies until the source has no message for the idle timeout. Messages are read in batches of up to
     * {@code batchSize}, and each batch is published in as few calls as the target's publish limit allows, in order.
     * Neither the consumer nor the producer is closed. If a publish fails, the call that failed is not copied (the
     * calls before it are), and the exception is thrown; a message that alone exceeds the target's limits fails the
     * copy with {@link IllegalArgumentException}, since the target cannot hold it.
     *
     * @param from          the consumer to read
     * @param to            the producer to write
     * @param batchSize     the most messages per publish, 1 or more
     * @param targetLimits  the target's limits, as its provider is configured ({@link RawdataLimits#of})
     * @param idleTimeout   how long to wait for a message before the copy is done
     * @return the number of messages copied
     * @throws IllegalArgumentException if the batch size is less than 1, the timeout is negative, or a message does not
     *                                  fit the target's limits
     * @throws InterruptedException     if the thread is interrupted while waiting
     * @throws RawdataClosedException   if the consumer or the producer is closed
     */
    public static long copy(RawdataConsumer from, RawdataProducer to, int batchSize, RawdataLimits targetLimits,
                            Duration idleTimeout) throws InterruptedException {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(targetLimits, "targetLimits");
        long copied = 0;
        for (; ; ) {
            List<RawdataMessage> batch = from.receiveUpTo(batchSize, idleTimeout);
            if (batch.isEmpty()) {
                return copied;
            }
            int start = 0;
            long size = 0;
            for (int i = 0; i < batch.size(); i++) {
                long next = RawdataLimits.size(batch.get(i));
                if (i > start && size + next > targetLimits.maxPublishBytes()) {
                    to.publish(batch.subList(start, i));
                    copied += i - start;
                    start = i;
                    size = 0;
                }
                size += next;
            }
            to.publish(batch.subList(start, batch.size()));
            copied += batch.size() - start;
        }
    }
}
