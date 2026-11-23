package io.descoped.rawdata.api.spi;

import io.descoped.rawdata.api.RawdataDuplicateIdException;
import io.descoped.rawdata.api.RawdataMessage;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The rule every provider keeps for ids: an id is unique within a topic. Publishing the same message again is a no-op;
 * a different message under a taken id is refused with {@link RawdataDuplicateIdException}.
 */
public final class RawdataDuplicates {

    private RawdataDuplicates() {
    }

    /**
     * Whether two messages are the same: the same id, position, ordering group and sequence number, and the same
     * content keys, in the same order, with the same bytes. Not {@link Object#equals}, which compares the keys as a
     * set: the order of the keys is stored, so a message with its keys in another order is another message.
     *
     * @param a a message
     * @param b another message
     * @return true if they are the same
     */
    public static boolean same(RawdataMessage a, RawdataMessage b) {
        if (!Objects.equals(a.id(), b.id()) || !a.position().equals(b.position())
                || !Objects.equals(a.orderingGroup(), b.orderingGroup()) || a.sequenceNumber() != b.sequenceNumber()
                || a.keys().size() != b.keys().size()) {
            return false;
        }
        Iterator<String> other = b.keys().iterator();
        for (String key : a.keys()) {
            if (!key.equals(other.next()) || !Arrays.equals(a.get(key), b.get(key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The messages of a call with a message repeated within it left out: the same message twice is written once, and
     * two different messages with one id are refused.
     *
     * @param topic    the topic, for the exception
     * @param messages the messages, every one with an id
     * @return the messages by id, in the call's order of their first occurrence
     * @throws RawdataDuplicateIdException if two different messages have one id
     */
    public static Map<UUID, RawdataMessage> distinct(String topic, List<RawdataMessage> messages) {
        Map<UUID, RawdataMessage> distinct = new LinkedHashMap<>();
        for (RawdataMessage message : messages) {
            RawdataMessage earlier = distinct.putIfAbsent(message.id(), message);
            if (earlier != null && !same(earlier, message)) {
                throw new RawdataDuplicateIdException(topic, message.id());
            }
        }
        return distinct;
    }
}
