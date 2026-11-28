package io.descoped.rawdata.api.spi;

import io.descoped.rawdata.api.RawdataMessage;

import java.util.Map;
import java.util.Objects;

/**
 * The limits of a publish, configured by the application and the same in every provider: every provider reads them
 * with {@link #of(Map)} from its configuration and checks every publish with {@link #requirePublishable}, before
 * anything is written. They are checked when a message is published, not when it is built, so a message already
 * stored is read whatever its size.
 * <ul>
 *     <li>{@value #MAX_POSITION_BYTES}: the longest position, in UTF-8 bytes (default
 *     {@value #DEFAULT_MAX_POSITION_BYTES}).</li>
 *     <li>{@value #MAX_PUBLISH_BYTES}: the most one publish call carries, in bytes: for each message the UTF-8 bytes of
 *     its position, ordering group and content keys, and the length of its values (default
 *     {@value #DEFAULT_MAX_PUBLISH_BYTES}, 256 MiB). One call is the unit a provider writes and a reader holds in
 *     memory, so the call is limited, not each message.</li>
 * </ul>
 * Both are at least 1, the position limit at most the publish limit, and the publish limit at most
 * {@value #MAX_PUBLISH_BYTES_CEILING} (512 MiB), which every store holds: below PostgreSQL's 1 GB field, and with an
 * upload window of at most 1 GiB, below the 2 GiB a Java array holds. A topic copies to another store only if the
 * target's limits are at least the source's.
 *
 * @param maxPositionBytes the longest position, in UTF-8 bytes
 * @param maxPublishBytes  the most one publish call carries, in bytes
 */
public record RawdataLimits(int maxPositionBytes, int maxPublishBytes) {

    /**
     * The configuration key of the longest position.
     */
    public static final String MAX_POSITION_BYTES = "rawdata.limits.max-position-bytes";

    /**
     * The configuration key of the most one publish call carries.
     */
    public static final String MAX_PUBLISH_BYTES = "rawdata.limits.max-publish-bytes";

    /**
     * The longest position by default, in UTF-8 bytes.
     */
    public static final int DEFAULT_MAX_POSITION_BYTES = 2000;

    /**
     * The most one publish call carries by default: 256 MiB.
     */
    public static final int DEFAULT_MAX_PUBLISH_BYTES = 256 * 1024 * 1024;

    /**
     * The highest publish limit that can be configured: 512 MiB.
     */
    public static final int MAX_PUBLISH_BYTES_CEILING = 512 * 1024 * 1024;

    /**
     * The limits when none are configured.
     */
    public static final RawdataLimits DEFAULT = new RawdataLimits(DEFAULT_MAX_POSITION_BYTES, DEFAULT_MAX_PUBLISH_BYTES);

    /**
     * Checks the limits.
     *
     * @param maxPositionBytes the longest position, in UTF-8 bytes
     * @param maxPublishBytes  the most one publish call carries, in bytes
     * @throws IllegalArgumentException if a limit is below 1, the position limit is above the publish limit, or the
     *                                  publish limit is above {@value #MAX_PUBLISH_BYTES_CEILING}
     */
    public RawdataLimits {
        if (maxPublishBytes < 1 || maxPublishBytes > MAX_PUBLISH_BYTES_CEILING) {
            throw new IllegalArgumentException("Configuration '" + MAX_PUBLISH_BYTES + "' must be 1 to "
                    + MAX_PUBLISH_BYTES_CEILING + ": " + maxPublishBytes);
        }
        if (maxPositionBytes < 1 || maxPositionBytes > maxPublishBytes) {
            throw new IllegalArgumentException("Configuration '" + MAX_POSITION_BYTES + "' must be 1 to '"
                    + MAX_PUBLISH_BYTES + "' (" + maxPublishBytes + "): " + maxPositionBytes);
        }
    }

    /**
     * Reads the limits from a provider's configuration; a missing key gives its default.
     *
     * @param configuration the configuration
     * @return the limits
     * @throws NullPointerException     if the configuration is null
     * @throws IllegalArgumentException if a value is not a whole number or out of range; the message names the key
     */
    public static RawdataLimits of(Map<String, String> configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return new RawdataLimits(number(configuration, MAX_POSITION_BYTES, DEFAULT_MAX_POSITION_BYTES),
                number(configuration, MAX_PUBLISH_BYTES, DEFAULT_MAX_PUBLISH_BYTES));
    }

    private static int number(Map<String, String> configuration, String key, int defaultValue) {
        String value = configuration.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value); // strict, as dynamic-configuration: no blanks, spaces or units
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Configuration '" + key + "' must be a whole number: '" + value + "'", e);
        }
    }

    /**
     * Checks the messages of a publish call against the limits.
     *
     * @param messages the messages
     * @throws NullPointerException     if the array or a message is null
     * @throws IllegalArgumentException if a position is longer than {@link #maxPositionBytes()}, or the call carries
     *                                  more than {@link #maxPublishBytes()}
     */
    public void requirePublishable(RawdataMessage... messages) {
        Objects.requireNonNull(messages, "messages");
        long total = 0;
        for (RawdataMessage message : messages) {
            Objects.requireNonNull(message, "one of the messages is null");
            long position = utf8Length(message.position());
            if (position > maxPositionBytes) {
                throw new IllegalArgumentException("A position is at most " + maxPositionBytes + " bytes in UTF-8 ('"
                        + MAX_POSITION_BYTES + "'): " + position);
            }
            total += position + utf8Length(message.orderingGroup());
            for (String key : message.keys()) {
                total += utf8Length(key) + message.length(key);
            }
            if (total > maxPublishBytes) {
                throw new IllegalArgumentException("A publish call carries at most " + maxPublishBytes + " bytes ('"
                        + MAX_PUBLISH_BYTES + "'): more in " + messages.length + " message(s)");
            }
        }
    }

    /**
     * The length of a string in UTF-8, as {@code getBytes(UTF_8).length} counts it (an unpaired surrogate is one
     * byte, the replacement {@code ?}), without encoding it.
     */
    static long utf8Length(String value) {
        if (value == null) {
            return 0;
        }
        long bytes = 0;
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                bytes += 1;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
