package io.descoped.rawdata.api.spi;

import io.descoped.rawdata.api.RawdataMessage;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The checks of a publish that every provider makes the same way, before anything is written: no null message, and
 * no position or content key longer than {@link RawdataMessage#MAX_POSITION_BYTES} and
 * {@link RawdataMessage#MAX_CONTENT_KEY_BYTES}. They are made when a message is published, not when it is built, so
 * a message already stored is read whatever its length.
 */
public final class RawdataLimits {

    private RawdataLimits() {
    }

    /**
     * Checks the messages of a publish.
     *
     * @param messages the messages
     * @throws NullPointerException     if the array or a message is null
     * @throws IllegalArgumentException if a position or content key is too long
     */
    public static void requirePublishable(RawdataMessage... messages) {
        Objects.requireNonNull(messages, "messages");
        for (RawdataMessage message : messages) {
            Objects.requireNonNull(message, "one of the messages is null");
            requireAtMost(message.position(), RawdataMessage.MAX_POSITION_BYTES, "A position");
            for (String key : message.keys()) {
                requireAtMost(key, RawdataMessage.MAX_CONTENT_KEY_BYTES, "A content key");
            }
        }
    }

    private static void requireAtMost(String value, int limit, String what) {
        if (value != null && value.length() > limit / 3) { // fewer chars than limit / 3 are within it in UTF-8
            int bytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > limit) {
                throw new IllegalArgumentException(what + " is at most " + limit + " bytes in UTF-8: " + bytes);
            }
        }
    }
}
