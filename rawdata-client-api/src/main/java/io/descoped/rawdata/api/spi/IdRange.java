package io.descoped.rawdata.api.spi;

import io.descoped.rawdata.api.RawdataIds;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * The ids written in a range of time: from {@code from}, inclusive, to {@code to}, exclusive, in
 * {@link RawdataIds#ORDER}. Providers use it to search for a position near a time.
 *
 * @param from the smallest id in the range
 * @param to   the smallest id after the range
 */
public record IdRange(UUID from, UUID to) {

    private static final Duration LONGEST = Duration.ofMillis(Long.MAX_VALUE);

    /**
     * Creates the range.
     *
     * @param from the smallest id in the range
     * @param to   the smallest id after the range
     */
    public IdRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    /**
     * Returns the ids written in {@code [approxTimestamp - tolerance, approxTimestamp + tolerance]}, by the
     * millisecond. A range beyond the times an id can hold is cut to them, never overflows.
     *
     * @param approxTimestamp the time in milliseconds since 1970-01-01T00:00Z
     * @param tolerance       how far from it, zero or more
     * @return the range
     * @throws IllegalArgumentException if the tolerance is negative
     */
    public static IdRange around(long approxTimestamp, Duration tolerance) {
        Objects.requireNonNull(tolerance, "tolerance");
        if (tolerance.isNegative()) {
            throw new IllegalArgumentException("tolerance must not be negative: " + tolerance);
        }
        long millis = tolerance.compareTo(LONGEST) >= 0 ? Long.MAX_VALUE : tolerance.toMillis();
        long lower = approxTimestamp < Long.MIN_VALUE + millis ? Long.MIN_VALUE : approxTimestamp - millis;
        long upper = approxTimestamp > Long.MAX_VALUE - millis - 1 ? Long.MAX_VALUE : approxTimestamp + millis + 1;
        return new IdRange(RawdataIds.beginningOf(lower), RawdataIds.beginningOf(upper));
    }
}
