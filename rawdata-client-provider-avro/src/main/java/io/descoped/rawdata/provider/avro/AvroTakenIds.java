package io.descoped.rawdata.provider.avro;

import io.descoped.rawdata.api.RawdataIds;
import io.descoped.rawdata.api.RawdataMessage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds whether an id is taken in a topic's uploaded segments, for its writer, and with which message: a digest of
 * the message, so a repeat of the same message can be told from a different one without keeping messages in memory.
 * Segments never change once uploaded, so their summaries and ids are kept: the summaries of the segments looked at,
 * and the ids of the last few segments read. A segment may hold an id if its lowest id is at or below it and the
 * topic's greatest id up to and with it is at or above it; an ascending run of ids, such as a copy run again, reads
 * each segment of the target about once. The first lookup reads the header of every segment from the first that may
 * hold the id to the last, one ranged read each; ids in random order may stream a segment per lookup. Used under
 * the topic's publish lock.
 */
final class AvroTakenIds {

    /** How many segments' ids are kept. */
    static final int SEGMENTS_KEPT = 4;

    private final AvroRawdataClient client;
    private final String topic;
    private final Map<Long, AvroSegments.Summary> summaries = new HashMap<>();
    private final Map<Long, Map<UUID, byte[]>> ids = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<UUID, byte[]>> eldest) {
            return size() > SEGMENTS_KEPT;
        }
    };

    AvroTakenIds(AvroRawdataClient client, String topic) {
        this.client = client;
        this.topic = topic;
    }

    /**
     * The digest of the message stored under an id in the segments up to and with the given one.
     *
     * @param id          the id
     * @param lastSegment the last uploaded segment, -1 if none
     * @return the digest, or empty if the id is not in those segments
     */
    Optional<byte[]> find(UUID id, long lastSegment) {
        long low = 0;
        long high = lastSegment + 1; // the first segment whose greatest id so far reaches the id, in [low, high]
        while (low < high) {
            long middle = (low + high) >>> 1;
            if (RawdataIds.ORDER.compare(summary(middle).lastId(), id) >= 0) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        for (long segment = low; segment <= lastSegment; segment++) {
            if (RawdataIds.ORDER.compare(summary(segment).firstId(), id) <= 0) {
                byte[] digest = idsOf(segment).get(id);
                if (digest != null) {
                    return Optional.of(digest);
                }
            }
        }
        return Optional.empty();
    }

    private AvroSegments.Summary summary(long segment) {
        return summaries.computeIfAbsent(segment, s -> client.summaryOf(topic, s));
    }

    /**
     * The digest of the message under an id in one segment, if the segment is uploaded and holds it.
     *
     * @param segment the segment
     * @param id      the id
     * @return the digest, or empty if the segment does not exist yet or does not hold the id
     */
    Optional<byte[]> findIn(long segment, UUID id) {
        Map<UUID, byte[]> kept = ids.get(segment);
        if (kept == null) {
            AvroSegmentReader reader = client.reader(topic, segment);
            if (reader == null) {
                return Optional.empty();
            }
            kept = read(reader, segment);
        }
        return Optional.ofNullable(kept.get(id));
    }

    private Map<UUID, byte[]> idsOf(long segment) {
        Map<UUID, byte[]> kept = ids.get(segment);
        return kept != null ? kept : read(client.existing(topic, segment), segment);
    }

    private Map<UUID, byte[]> read(AvroSegmentReader reader, long segment) {
        Map<UUID, byte[]> read = new HashMap<>();
        RawdataMessage message;
        while ((message = client.next(reader, topic, segment)) != null) {
            read.putIfAbsent(message.id(), digest(message)); // the first, if a topic from before 3.1.0 has it twice
        }
        ids.put(segment, read);
        return read;
    }

    /**
     * A digest of everything a message holds but its id: position, ordering group, sequence number, and the content
     * keys in order with their bytes. Two messages under one id are the same if their digests are.
     *
     * @param message the message
     * @return the SHA-256 digest
     */
    static byte[] digest(RawdataMessage message) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256", e);
        }
        text(sha, message.position());
        String orderingGroup = message.orderingGroup();
        sha.update((byte) (orderingGroup == null ? 0 : 1));
        if (orderingGroup != null) {
            text(sha, orderingGroup);
        }
        sha.update(ByteBuffer.allocate(Long.BYTES + Integer.BYTES).putLong(message.sequenceNumber())
                .putInt(message.keys().size()).array());
        for (String key : message.keys()) {
            text(sha, key);
            bytes(sha, message.get(key));
        }
        return sha.digest();
    }

    private static void text(MessageDigest sha, String text) {
        bytes(sha, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(MessageDigest sha, byte[] bytes) {
        sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        sha.update(bytes);
    }
}
