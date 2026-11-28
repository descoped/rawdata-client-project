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
        assertEquals(new RawdataLimits(10, 20), RawdataLimits.of(Map.of(
                RawdataLimits.MAX_POSITION_BYTES, "10", RawdataLimits.MAX_PUBLISH_BYTES, "20")));
    }

    @Test
    void valuesOutOfRangeOrMalformedAreRefusedNamingTheKey() {
        int ceiling = RawdataLimits.MAX_PUBLISH_BYTES_CEILING;
        assertRefused(RawdataLimits.MAX_PUBLISH_BYTES, Integer.toString(ceiling + 1));
        assertRefused(RawdataLimits.MAX_PUBLISH_BYTES, "0");
        assertRefused(RawdataLimits.MAX_POSITION_BYTES, "0");
        assertRefused(RawdataLimits.MAX_POSITION_BYTES, Integer.toString(RawdataLimits.DEFAULT_MAX_PUBLISH_BYTES + 1));
        assertRefused(RawdataLimits.MAX_POSITION_BYTES, " 10");
        assertRefused(RawdataLimits.MAX_POSITION_BYTES, "");
        assertRefused(RawdataLimits.MAX_PUBLISH_BYTES, "1 MiB");
        assertRefused(RawdataLimits.MAX_PUBLISH_BYTES, "99999999999");
        assertDoesNotThrow(() -> new RawdataLimits(ceiling, ceiling));
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
        assertThrows(IllegalArgumentException.class, () -> limits.requirePublishable(message("æøåa")));
        assertThrows(IllegalArgumentException.class, () -> limits.requirePublishable(message("€€a")));
        assertThrows(IllegalArgumentException.class, () -> limits.requirePublishable(message("\uD83D\uDE00abc")));
        String unpaired = "\uD800abcde"; // an unpaired surrogate is encoded as one byte, '?'
        assertEquals(6, unpaired.getBytes(StandardCharsets.UTF_8).length);
        limits.requirePublishable(message(unpaired));
        assertThrows(IllegalArgumentException.class, () -> limits.requirePublishable(message(unpaired + "f")));
    }

    @Test
    void aCallCountsPositionsOrderingGroupsKeysAndValuesOfAllItsMessages() {
        RawdataLimits limits = new RawdataLimits(10, 20);
        // 2 + 2 + 1 + 5 = 10 bytes per message
        RawdataMessage ten = RawdataMessage.builder().position("pp").orderingGroup("gg").put("k", new byte[5]).build();
        limits.requirePublishable(ten, ten);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> limits.requirePublishable(ten, ten, message("x")));
        assertTrue(e.getMessage().contains(RawdataLimits.MAX_PUBLISH_BYTES), e.getMessage());
        RawdataMessage eleven = RawdataMessage.builder().position("pp").orderingGroup("gg").put("kk", new byte[5]).build();
        assertThrows(IllegalArgumentException.class, () -> limits.requirePublishable(ten, eleven));
        limits.requirePublishable(); // an empty call carries nothing
    }

    @Test
    void nullsAreRefused() {
        assertThrows(NullPointerException.class, () -> RawdataLimits.DEFAULT.requirePublishable((RawdataMessage[]) null));
        assertThrows(NullPointerException.class, () -> RawdataLimits.DEFAULT.requirePublishable(message("a"), null));
        assertThrows(NullPointerException.class, () -> RawdataLimits.of(null));
    }

    @Test
    void theLengthOfContentIsReadWithoutACopy() {
        RawdataMessage message = RawdataMessage.builder().position("p").put("k", new byte[7]).put("e", new byte[0]).build();
        assertEquals(7, message.length("k"));
        assertEquals(0, message.length("e"));
        assertEquals(-1, message.length("missing"));
    }

    private static RawdataMessage message(String position) {
        return RawdataMessage.builder().position(position).build();
    }
}
