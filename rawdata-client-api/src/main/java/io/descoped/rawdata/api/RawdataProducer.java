package io.descoped.rawdata.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes messages on one topic. A producer may be used from several threads.
 */
public interface RawdataProducer extends AutoCloseable {

    /**
     * Returns the topic this producer publishes on.
     *
     * @return the topic
     */
    String topic();

    /**
     * Publishes messages, in order. A message without an id is given one that is greater than every id already in
     * the topic. The messages of one call are written together: either all of them or none. As
     * {@link #publish(RawdataMessage...)}, also for a given id that is taken.
     *
     * @param messages the messages
     * @throws RawdataClosedException      if the producer or its client is closed
     * @throws RawdataDuplicateIdException if a given id is taken by a different message
     */
    default void publish(List<RawdataMessage> messages) throws RawdataClosedException {
        publish(messages.toArray(new RawdataMessage[0]));
    }

    /**
     * Publishes messages, in order. A message without an id is given one that is greater than every id already in
     * the topic. A message with an id keeps it, in any order: it may be lower than ids already in the topic, and a
     * consumer already past it does not get it. The messages of one call are written together: either all of them or
     * none.
     * <p>
     * An id is unique within a topic. Publishing the same message again (the same id, position, ordering group,
     * sequence number, and content keys and bytes in the same order) is a no-op, also within one call, so a writer that
     * recovers can repeat its last publish. A different message under an id that is taken is refused with
     * {@link RawdataDuplicateIdException}, and nothing of the call is written.
     *
     * @param messages the messages
     * @throws RawdataClosedException      if the producer or its client is closed
     * @throws RawdataDuplicateIdException if a given id is taken by a different message
     */
    void publish(RawdataMessage... messages) throws RawdataClosedException;

    /**
     * Publishes messages asynchronously, as {@link #publish(RawdataMessage...)}.
     *
     * @param messages the messages
     * @return a future that completes when the messages are written, or exceptionally when the write fails
     */
    default CompletableFuture<Void> publishAsync(List<RawdataMessage> messages) {
        return publishAsync(messages.toArray(new RawdataMessage[0]));
    }

    /**
     * Publishes messages asynchronously, as {@link #publish(RawdataMessage...)}.
     *
     * @param messages the messages
     * @return a future that completes when the messages are written, or exceptionally when the write fails
     */
    CompletableFuture<Void> publishAsync(RawdataMessage... messages);

    /**
     * Whether the producer is closed.
     *
     * @return true after {@link #close()} on the producer or its client
     */
    boolean isClosed();

    /**
     * Closes the producer. Calling it again does nothing.
     */
    @Override
    void close();
}
