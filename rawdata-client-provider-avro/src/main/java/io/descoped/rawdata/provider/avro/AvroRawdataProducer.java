package io.descoped.rawdata.provider.avro;

import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataWriterConflictException;
import io.descoped.rawdata.api.spi.OrderedRawdataProducer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends a call's messages to its topic's upload window ({@link AvroTopicWriter}); they are visible once the window
 * is uploaded. Ids are given after the greatest id of the topic; a given id is kept, and one already taken is checked
 * (the same message again is a no-op, a different one is refused). A call is written whole into one window, or not at
 * all. Another process writing the topic makes publishes fail with
 * {@link RawdataWriterConflictException}. Closing the producer uploads the open window and waits, as in 2.x.
 */
final class AvroRawdataProducer extends OrderedRawdataProducer {

    private final AvroRawdataClient client;

    AvroRawdataProducer(AvroRawdataClient client, String topic) {
        super(topic);
        this.client = client;
    }

    @Override
    protected void write(RawdataMessage[] batch) {
        String topic = topic();
        Map<RawdataMessage, byte[]> given = new IdentityHashMap<>(); // hashed before the lock, as producers may in parallel
        for (RawdataMessage message : batch) {
            if (message.id() != null) {
                given.put(message, AvroTakenIds.digest(message));
            }
        }
        ReentrantLock lock = client.publishLock(topic); // producers of this client take turns on a topic
        lock.lock();
        try {
            checkOpen();
            client.writer(topic).publish(last -> withIds(batch, last), given);
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected boolean isClientClosed() {
        return client.isClosed();
    }

    @Override
    protected Executor executor() {
        return client.executor();
    }

    @Override
    protected void closed() {
        client.removed(this);
        client.producerClosed(topic());
    }
}
