package io.descoped.rawdata.provider.filesystem;

import io.descoped.rawdata.provider.avro.AvroStore;
import io.descoped.rawdata.provider.avro.AvroTopicFolders;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Topics in a folder: {@code <folder>/<topic folder>/segments/<number>.avro}, the number in 19 digits, and
 * {@code <folder>/<topic folder>/metadata/<name>}. A file is written under a temporary name ({@code .*.tmp}) in the
 * topic's folder first; a segment is then hard-linked to its name, which fails if the name exists, and a metadata
 * entry is moved over its name. Readers so never see part of a file, and two writers never both create a segment;
 * creating an existing segment again with the same bytes counts as created, as in every store.
 * A writer that crashes between the two steps leaves its temporary file behind; nothing reads it, and it may be
 * deleted. The folder must be on a file system with hard links and atomic moves, as local file systems on Linux,
 * macOS and Windows are.
 * <p>
 * The storage folder itself is never created here (the initializer creates it): a folder that is gone, removed or
 * unmounted, fails every call with {@link NoSuchFileException}, as a missing bucket does, instead of reading as empty
 * topics. A file is forced to disk before it is linked or moved to its name, and the folder that holds the name after
 * it, so a segment or metadata entry that a call reported written survives a power loss, not only a crash of the
 * process.
 * <p>
 * The topic folder is the topic's name, kept apart from others on case-insensitive file systems (macOS, Windows) and
 * valid on Windows: an upper-case letter is written as {@code ^} and the letter in lower case, a trailing {@code .}
 * gets a {@code ^} after it, and a Windows device name ({@code con}, {@code nul}, {@code com1}...) gets {@code ~}
 * before it. {@code ^} and {@code ~} are not in topic names, so different topics never share a folder.
 */
final class FilesystemAvroStore implements AvroStore {

    private static final int MAX_NAME_LENGTH = 255;
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private final Path folder;

    FilesystemAvroStore(Path folder) {
        this.folder = folder;
    }

    @Override
    public boolean create(String topic, long segment, byte[] content) throws IOException {
        Path target = segment(topic, segment);
        createBelowFolder(target.getParent());
        Path temporary = temporaryFile(topic, content);
        try {
            Files.createLink(target, temporary);
            forceFolder(target.getParent());
            return true;
        } catch (FileAlreadyExistsException e) {
            // As in every store, the same bytes mean this writer's segment, since an Avro file has a random sync
            // marker. They are compared on disk, never read into memory.
            return Files.size(target) == content.length && Files.mismatch(target, temporary) == -1;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Opens a segment, pinned to its file: a segment's name is only ever linked once, never replaced, and each read
     * checks the name still leads to the file that was opened: the same file key (an inode; null on Windows), size and
     * modification time. A file system may give a new file a deleted file's inode, so the key alone is not enough.
     */
    @Override
    public Optional<Segment> open(String topic, long segment) throws IOException {
        Path file = segment(topic, segment);
        BasicFileAttributes opened;
        try {
            opened = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            requireFolder();
            return Optional.empty();
        }
        Object key = opened.fileKey();
        long size = opened.size();
        FileTime modified = opened.lastModifiedTime();
        return Optional.of(new Segment() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public byte[] read(long offset, int length) throws IOException {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    BasicFileAttributes now = Files.readAttributes(file, BasicFileAttributes.class);
                    if (!Objects.equals(key, now.fileKey()) || size != now.size() || !modified.equals(now.lastModifiedTime())) {
                        throw new IOException("Segment " + file + " is no longer the file that was opened");
                    }
                    ByteBuffer bytes = ByteBuffer.allocate((int) Math.max(0, Math.min(length, size - offset)));
                    while (bytes.hasRemaining()) {
                        if (channel.read(bytes, offset + bytes.position()) < 0) {
                            throw new IOException("Segment " + file + " is shorter than when it was opened");
                        }
                    }
                    return bytes.array();
                } catch (NoSuchFileException e) {
                    throw new IOException("Segment " + file + " was removed while it was read", e);
                }
            }
        });
    }

    /**
     * Whether a segment exists; a failure of the file system is thrown, never read as a missing segment, which would
     * make a writer take an earlier segment for the topic's end.
     */
    @Override
    public boolean exists(String topic, long segment) throws IOException {
        try {
            Files.readAttributes(segment(topic, segment), BasicFileAttributes.class);
            return true;
        } catch (NoSuchFileException e) {
            requireFolder();
            return false;
        }
    }

    @Override
    public Set<String> metadataNames(String topic) throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(metadata(topic))) {
            files.forEach(file -> names.add(file.getFileName().toString()));
        } catch (NoSuchFileException e) {
            requireFolder(); // otherwise no metadata yet
        }
        return names;
    }

    @Override
    public Optional<byte[]> readMetadata(String topic, String name) throws IOException {
        return readIfExists(metadata(topic, name));
    }

    private Optional<byte[]> readIfExists(Path file) throws IOException {
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (NoSuchFileException e) {
            requireFolder();
            return Optional.empty();
        }
    }

    @Override
    public void writeMetadata(String topic, String name, byte[] value) throws IOException {
        Path target = metadata(topic, name);
        createBelowFolder(target.getParent());
        Path temporary = temporaryFile(topic, value);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceFolder(target.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void deleteMetadata(String topic, String name) throws IOException {
        if (!Files.deleteIfExists(metadata(topic, name))) {
            requireFolder();
        }
    }

    @Override
    public void close() {
        // nothing to release
    }

    /**
     * The storage folder must be there: a missing one is a missing store, never empty topics.
     */
    private void requireFolder() throws NoSuchFileException {
        if (!Files.isDirectory(folder)) {
            throw new NoSuchFileException(folder.toString(), null, "the storage folder does not exist");
        }
    }

    /**
     * Creates the folders below the storage folder, one level at a time from it down, so a storage folder that is gone
     * fails with {@link NoSuchFileException} and is never created again, not even when it goes while this runs. Each
     * folder created is forced into its parent, so a new topic's folders survive a power loss with its first segment.
     */
    private void createBelowFolder(Path below) throws IOException {
        requireFolder();
        Path parent = folder;
        for (Path name : folder.relativize(below)) {
            Path next = parent.resolve(name);
            try {
                Files.createDirectory(next);
                forceFolder(parent);
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(next)) {
                    throw e;
                }
            }
            parent = next;
        }
    }

    /**
     * Forces a folder's entries to disk, so a name just linked or moved into it survives a power loss. Windows cannot
     * open a folder for this, and keeps its directory entries in its own journal.
     */
    private static void forceFolder(Path directory) throws IOException {
        if (WINDOWS) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private Path temporaryFile(String topic, byte[] content) throws IOException {
        Path topicFolder = topicFolder(topic);
        createBelowFolder(topicFolder);
        Path temporary = Files.createTempFile(topicFolder, ".", ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true); // on disk before its name is linked or moved, and the window deleted
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        return temporary;
    }

    private Path topicFolder(String topic) {
        return folder.resolve(AvroTopicFolders.folderName(topic));
    }

    private Path segment(String topic, long segment) {
        return topicFolder(topic).resolve("segments").resolve(String.format("%019d.avro", segment));
    }

    private Path metadata(String topic) {
        return topicFolder(topic).resolve("metadata");
    }

    private Path metadata(String topic, String name) {
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A metadata name of the filesystem store is at most " + MAX_NAME_LENGTH
                    + " characters, a file name: " + name.length());
        }
        return metadata(topic).resolve(name);
    }

    @Override
    public String toString() {
        return "FilesystemAvroStore{folder=" + folder + '}';
    }
}
