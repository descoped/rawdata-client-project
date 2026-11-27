package io.descoped.rawdata.provider.postgres;

import io.descoped.rawdata.api.RawdataIds;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataWriterConflictException;
import io.descoped.rawdata.api.spi.OrderedRawdataProducer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes a call's messages in one transaction that holds the topic's advisory lock: it reads the topic's greatest
 * id and gives new ids after it; given ids are stored as they are, in any order. Only one process writes a topic: if
 * the greatest id is not the greatest this client wrote (after its first publish to the topic), another process
 * wrote, and the publish fails with {@link RawdataWriterConflictException}, writing nothing.
 * <p>
 * An id is unique within a topic. Publishing a message again is a no-op: a message whose id is already stored, with
 * the same position, ordering fields and content, is not written twice (a writer that recovers repeats its last
 * publish). A different message under a stored id is refused with
 * {@link io.descoped.rawdata.api.RawdataDuplicateIdException}, and nothing of the call is written. A message repeated
 * is already at or below the topic's greatest id, so the one-writer check is not changed.
 * <p>
 * A position and a content key are in btree indexes, which hold the API's limits of
 * {@link RawdataMessage#MAX_POSITION_BYTES} and {@link RawdataMessage#MAX_CONTENT_KEY_BYTES} bytes. A publish that
 * waited for its turn while its producer or client closed writes nothing and throws
 * {@link io.descoped.rawdata.api.RawdataClosedException}. After a publish, the client's waiting consumers of the topic
 * read again at once, unless {@code rawdata.postgres.consumer.wake-on-publish} is {@code false}.
 */
final class PostgresRawdataProducer extends OrderedRawdataProducer {

    private final PostgresRawdataClient client;

    PostgresRawdataProducer(PostgresRawdataClient client, String topic) {
        super(topic);
        this.client = client;
    }

    @Override
    protected void write(RawdataMessage[] batch) {
        String topic = topic();
        boolean given = Arrays.stream(batch).anyMatch(message -> message.id() != null);
        ReentrantLock lock = client.publishLock(topic);
        lock.lock();
        try {
            checkOpen(); // closed while this publish waited for its turn: write nothing
            UUID wrote = client.jdbc().transaction("publish to topic " + topic, connection -> {
                lockTopic(connection, topic);
                UUID stored = PostgresMessages.lastId(connection, topic);
                UUID last = stored == null ? RawdataIds.beginningOfTime() : stored;
                UUID known = client.written(topic);
                if (known != null && !known.equals(last)) {
                    throw new RawdataWriterConflictException(topic); // another process published since; rolled back
                }
                List<RawdataMessage> messages = withIds(batch, last);
                // only a given id can be stored already: generated ones follow the topic's greatest id
                PostgresMessages.insert(connection, topic, given ? PostgresMessages.notStored(connection, topic, messages) : messages);
                return greatest(last, messages);
            });
            client.written(topic, wrote);
        } finally {
            lock.unlock();
        }
        client.published(topic);
    }


    private static void lockTopic(Connection connection, String topic) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            lock.setInt(1, PostgresSchema.TOPIC_LOCK);
            lock.setString(2, topic);
            lock.execute();
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
    }
}
