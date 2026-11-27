package io.descoped.rawdata.provider.avro;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window's configuration limits.
 */
class AvroUploadWindowTest {

    private static Map<String, String> withMaxBytes(String value) {
        return Map.of(AvroUploadWindow.LOCAL_TEMP_FOLDER, "buffer", AvroUploadWindow.MAX_BYTES, value);
    }

    /**
     * {@code avro-file.max.bytes} is at most 1 GiB: an upload holds the whole window in one array.
     */
    @Test
    void maxBytesIsAtMostOneGibibyte() {
        assertEquals(1L << 30, AvroUploadWindow.of(withMaxBytes("1073741824")).maxBytes());
        Map<String, String> oneMore = withMaxBytes("1073741825");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> AvroUploadWindow.of(oneMore));
        assertTrue(refused.getMessage().contains(AvroUploadWindow.MAX_BYTES), refused.getMessage());
        Map<String, String> twoGibibytes = withMaxBytes("2147483648");
        assertThrows(IllegalArgumentException.class, () -> AvroUploadWindow.of(twoGibibytes));
        Map<String, String> zero = withMaxBytes("0");
        assertThrows(IllegalArgumentException.class, () -> AvroUploadWindow.of(zero));
    }

    /**
     * Without {@code avro-file.max.bytes}, a window closes at 64 MiB.
     */
    @Test
    void maxBytesDefaultsTo64Mebibytes() {
        assertEquals(64L << 20, AvroUploadWindow.of(Map.of(AvroUploadWindow.LOCAL_TEMP_FOLDER, "buffer")).maxBytes());
    }
}
