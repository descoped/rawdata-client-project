package io.descoped.rawdata.api.test;

import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.spi.RawdataLimits;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawdataLimitsTest {

    @Test
    void missingKeysGiveTheDefaults() {
        assertEquals(RawdataLimits.DEFAULT, RawdataLimits.of(Map.of()));
        assertEquals(2000, RawdataLimits.DEFAULT.maxPositionBytes());
        assertEquals(256 * 1024 * 1024, RawdataLimits.DEFAULT.maxPublishBytes());
    }

    @Test
    void configuredValuesAreRead() {
        assertEquals(new RawdataLimits(10, 100), RawdataLimits.of(Map.of(
                RawdataLimits.MAX_POSITION_BYTES, "10", RawdataLimits.MAX_PUBLISH_BYTES, "100")));
    }

    @Test
    void valuesOutOfRangeOrMalformedAreRefusedNamingTheKey() {
        int ceiling = RawdataLimits.MAX_PUBLISH_BYTES_CEILING;
        assertRefused(RawdataLimits.MAX_PUBLISH_BYTES, Integer.toString(ceiling + 1));
        assertRefused(RawdataLimits.MAX_PUBLISH_BYTES, "0");
        assertRefused(RawdataLimits.MAX_POSITION_BYTES, "0");
        // the publish limit must hold a message with the longest position
        int most = RawdataLimits.DEFAULT_MAX_PUBLISH_BYTES - RawdataLimits.MESSAGE_OVERHEAD_BYTES;
        assertDoesNotThrow(() -> new RawdataLimits(most, RawdataLimits.DEFAULT_MAX_PUBLISH_BYTES));
        assertRefused(RawdataLimits.MAX_POSITION_BYTES, Integer.toString(most + 1));
        assertRefused(RawdataLimits.MAX_POSITION_BYTES, " 10");
        assertRefused(RawdataLimits.MAX_POSITION_BYTES, "");
        assertRefused(RawdataLimits.MAX_PUBLISH_BYTES, "1 MiB");
        assertRefused(RawdataLimits.MAX_PUBLISH_BYTES, "99999999999");
        assertDoesNotThrow(() -> new RawdataLimits(ceiling - RawdataLimits.MESSAGE_OVERHEAD_BYTES, ceiling));
    }

    private static void assertRefused(String key, String value) {
        Map<String, String> configuration = new HashMap<>();
        configuration.put(key, value);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RawdataLimits.of(configuration));
        assertTrue(e.getMessage().contains(key), e.getMessage());
    }

    @Test
    void aPositionIsMeasuredInUtf8Bytes() {
        RawdataLimits limits = new RawdataLimits(6, 1000);
        limits.requirePublishable(message("æøå")); // 2 bytes each
        limits.requirePublishable(message("€€")); // 3 bytes each
        limits.requirePublishable(message("\uD83D\uDE00ab")); // a pair of surrogates is 4 bytes, then 1 each
        assertRefused(limits, message("æøåa"));
        assertRefused(limits, message("€€a"));
        assertRefused(limits, message("\uD83D\uDE00abc"));
        String unpaired = "\uD800abcde"; // an unpaired surrogate is encoded as one byte, '?'
        assertEquals(6, unpaired.getBytes(StandardCharsets.UTF_8).length);
        limits.requirePublishable(message(unpaired));
        assertRefused(limits, message(unpaired + "f"));
    }

    @Test
    void aCallCountsPositionsOrderingGroupsKeysValuesAndFramingOfAllItsMessages() {
        int overhead = RawdataLimits.MESSAGE_OVERHEAD_BYTES + RawdataLimits.CONTENT_OVERHEAD_BYTES;
        // 2 + 2 + 1 + 5 = 10 bytes per message, plus its framing and its one key's
        RawdataMessage ten = RawdataMessage.builder().position("pp").orderingGroup("gg").put("k", new byte[5]).build();
        assertEquals(10 + overhead, RawdataLimits.size(ten));
        RawdataLimits limits = new RawdataLimits(10, 2 * (10 + overhead));
        limits.requirePublishable(ten, ten);
        RawdataMessage x = message("x");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> limits.requirePublishable(ten, ten, x));
        assertTrue(e.getMessage().contains(RawdataLimits.MAX_PUBLISH_BYTES), e.getMessage());
        RawdataMessage eleven = RawdataMessage.builder().position("pp").orderingGroup("gg").put("kk", new byte[5]).build();
        assertRefused(limits, ten, eleven);
        limits.requirePublishable(); // an empty call carries nothing
    }

    @Test
    void nullsAreRefused() {
        assertThrows(NullPointerException.class, () -> RawdataLimits.DEFAULT.requirePublishable((RawdataMessage[]) null));
        RawdataMessage a = message("a");
        assertThrows(NullPointerException.class, () -> RawdataLimits.DEFAULT.requirePublishable(a, null));
        assertThrows(NullPointerException.class, () -> RawdataLimits.of(null));
    }

    @Test
    void theLengthOfContentIsReadWithoutACopy() {
        RawdataMessage message = RawdataMessage.builder().position("p").put("k", new byte[7]).put("e", new byte[0]).build();
        assertEquals(7, message.length("k"));
        assertEquals(0, message.length("e"));
        assertEquals(-1, message.length("missing"));
    }

    private static void assertRefused(RawdataLimits limits, RawdataMessage... messages) {
        assertThrows(IllegalArgumentException.class, () -> limits.requirePublishable(messages));
    }

    private static RawdataMessage message(String position) {
        return RawdataMessage.builder().position(position).build();
    }
}
