package io.descoped.rawdata.provider.filesystem;

import io.descoped.rawdata.provider.avro.AvroStore;
import io.descoped.rawdata.provider.avro.AvroTopicFolders;
import io.descoped.rawdata.tck.avro.AvroStoreContractTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FilesystemStoreContractTest extends AvroStoreContractTest {

    @TempDir
    static Path folder;

    @Override
    protected AvroStore createStore() {
        return new FilesystemAvroStore(folder);
    }

    /**
     * A storage folder that is gone (removed, or a mount that dropped), which the store never creates again.
     */
    @Override
    protected AvroStore createMissingStore() {
        return new FilesystemAvroStore(folder.resolve("gone-" + UUID.randomUUID()));
    }

    /**
     * A failure of the file system is thrown by {@code exists}, never read as a missing segment, which would make a
     * writer take an earlier segment for the topic's end. POSIX permissions, which root ignores.
     *
     * @throws IOException if the file system fails
     */
    @Test
    void existsThrowsWhenTheFileSystemFails() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        assumeFalse("root".equals(System.getProperty("user.name")), "root ignores permissions");
        assertTrue(store.create(topic, 0, new byte[]{1}));
        Path segments = folder.resolve(AvroTopicFolders.folderName(topic)).resolve("segments");
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(segments);
        Files.setPosixFilePermissions(segments, Set.of());
        try {
            assertThrows(IOException.class, () -> store.exists(topic, 1));
        } finally {
            Files.setPosixFilePermissions(segments, before);
        }
        assertFalse(store.exists(topic, 1));
    }

    /**
     * Creating a segment whose number holds other bytes answers false and leaves no temporary file behind.
     *
     * @throws IOException if the file system fails
     */
    @Test
    void aTakenSegmentLeavesNoTemporaryFile() throws IOException {
        assertTrue(store.create(topic, 0, new byte[]{1, 2, 3}));
        assertFalse(store.create(topic, 0, new byte[]{1, 2, 4}));
        assertFalse(store.create(topic, 0, new byte[]{1, 2}));
        assertTrue(store.create(topic, 0, new byte[]{1, 2, 3}), "its own bytes count as created");
        try (Stream<Path> files = Files.list(folder.resolve(AvroTopicFolders.folderName(topic)))) {
            assertEquals(List.of(), files.filter(f -> f.getFileName().toString().endsWith(".tmp")).toList());
        }
    }

    /**
     * An opened segment reads the file it was opened on: once the file is replaced (only by hand, the engine never
     * replaces a segment), its reads fail instead of mixing two files.
     *
     * @throws IOException if the file system fails
     */
    @Test
    void anOpenedSegmentIsPinnedToItsFile() throws IOException {
        assertTrue(store.create(topic, 0, new byte[]{1, 2, 3}));
        AvroStore.Segment opened = store.open(topic, 0).orElseThrow();
        assertArrayEquals(new byte[]{2, 3}, opened.read(1, 2));
        Path file = folder.resolve(AvroTopicFolders.folderName(topic)).resolve("segments").resolve("0000000000000000000.avro");
        Files.delete(file);
        Files.write(file, new byte[]{9, 9, 9, 9}); // a new file; it may even get the old one's inode
        assertThrows(IOException.class, () -> opened.read(0, 3));
    }
}
