package io.descoped.rawdata.api;

import java.util.Set;

/**
 * Reads and writes the metadata of one topic: entries of a key and bytes, for example a checkpoint. The entries
 * belong to the topic, so every metadata client of the topic sees the same entries.
 * <p>
 * A key is any string without the NUL character (U+0000), the empty string included; the methods that take
 * a key check it with {@link #requireValidKey(String)}. A key is at most {@value #MAX_KEY_BYTES} bytes in UTF-8 in
 * every provider; a longer key is refused with {@link IllegalArgumentException} before the store is used.
 */
public interface RawdataMetadataClient {

    /**
     * The longest key, in UTF-8 bytes, the same in every provider: a file name holds {@code k} and the key in hex.
     */
    int MAX_KEY_BYTES = 127;

    /**
     * Checks a metadata key: not null, no NUL character, and at most {@value #MAX_KEY_BYTES} bytes in UTF-8.
     *
     * @param key the key
     * @return the key, unchanged
     * @throws NullPointerException     if the key is null
     * @throws IllegalArgumentException if the key has a NUL character or is too long
     */
    static String requireValidKey(String key) {
        RawdataDefaultMessage.requireNoNul(java.util.Objects.requireNonNull(key, "key"), "A metadata key");
        int bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("A metadata key is at most " + MAX_KEY_BYTES + " bytes in UTF-8: " + bytes);
        }
        return key;
    }

    /**
     * Returns the topic this metadata client belongs to.
     *
     * @return the topic
     */
    String topic();

    /**
     * Returns the keys of the topic's metadata entries.
     *
     * @return an unmodifiable snapshot of the keys
     * @throws RawdataClosedException if the client is closed
     */
    Set<String> keys();

    /**
     * Reads an entry.
     *
     * @param key the key
     * @return a copy of the value, or null if there is no entry with the key
     * @throws NullPointerException     if the key is null
     * @throws IllegalArgumentException if the key has a NUL character or is too long for the store
     * @throws RawdataClosedException   if the client is closed
     */
    byte[] get(String key);

    /**
     * Writes an entry, replacing any value the key had. The store keeps a copy of the array.
     *
     * @param key   the key
     * @param value the value
     * @return this metadata client
     * @throws NullPointerException     if the key or the value is null
     * @throws IllegalArgumentException if the key has a NUL character or is too long for the store
     * @throws RawdataClosedException   if the client is closed
     */
    RawdataMetadataClient put(String key, byte[] value);

    /**
     * Removes an entry; does nothing if there is none.
     *
     * @param key the key
     * @return this metadata client
     * @throws NullPointerException     if the key is null
     * @throws IllegalArgumentException if the key has a NUL character or is too long for the store
     * @throws RawdataClosedException   if the client is closed
     */
    RawdataMetadataClient remove(String key);
}
