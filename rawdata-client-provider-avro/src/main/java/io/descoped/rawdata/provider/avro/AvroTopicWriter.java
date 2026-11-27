package io.descoped.rawdata.provider.avro;

import io.descoped.rawdata.api.RawdataDuplicateIdException;
import io.descoped.rawdata.api.RawdataException;
import io.descoped.rawdata.api.RawdataIds;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataWriterConflictException;
import io.descoped.rawdata.api.spi.RawdataDuplicates;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericRecord;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The writer of one topic in one client: the upload window. Publishes append to the open window, a file in the
 * topic's folder under {@code local-temp-folder}. The window closes when it is {@code avro-file.max.seconds} old (a
 * timer closes it, so a quiet producer still uploads) or larger than {@code avro-file.max.bytes}, or when a producer
 * of the topic closes. A closed window is forced to disk and sealed (renamed {@code <segment>.sealed.avro}), then
 * uploaded in the background as the topic's next segment while publishes go on in a new window. Uploads of a topic run
 * one at a time, in segment order; a publish waits while {@code avro-file.max.pending-uploads} sealed windows wait.
 * <p>
 * <b>Whole calls.</b> An Avro block ends only between publish calls, and is then written to the file, so a crash
 * leaves whole calls in the file, never part of one. Every call ends its block, so a call that returned survives a
 * crash of the process. The file is forced to disk every {@code avro-file.sync.interval} bytes, so a crash of the
 * machine loses the calls written since: about that much, more after a larger call. A write that fails in the middle of a call leaves the window as a crash would: its
 * complete blocks in the file, for recovery.
 * <p>
 * <b>One writer.</b> The writer starts from the topic's end. A segment number that is taken when a window opens or
 * uploads means another process wrote the topic: the writer fails with {@link RawdataWriterConflictException}, its
 * windows are set aside ({@code .invalid}, never deleted) and every later publish fails.
 * <p>
 * <b>Unique ids.</b> A given id at or below the topic's greatest id is looked up before the call is written: in the
 * open window and the windows waiting for upload, and in the uploaded segments ({@link AvroTakenIds}). The windows
 * keep their ids until uploaded, with a digest only for a message given with its id; a generated id has none, as no
 * one can repeat it before it is seen. The same message again is left out; a different one is refused.
 * <p>
 * <b>Failures.</b> An upload that fails after the store's retries fails the writer: every later publish, flush and
 * close throws, and the sealed windows stay on disk.
 * <p>
 * <b>Recovery.</b> When the writer starts (when the client's first producer of the topic is created), it takes a lock
 * file in the topic's folder (one process per folder) and looks for windows a crash left behind. A sealed window is
 * uploaded unchanged, or, if the store already has it with the same bytes, removed. An open window is cut back to its
 * complete blocks and uploaded. A leftover is uploaded only where it belongs: right after the topic's end, after the id
 * it was written after. Otherwise it is set aside as {@code .invalid}, as 2.x left its temporary files, and the writer
 * fails fast with {@link RawdataWriterConflictException}; the next writer starts from the topic's end.
 */
final class AvroTopicWriter {

    private static final System.Logger LOG = System.getLogger(AvroTopicWriter.class.getName());
    private static final String OPEN_SUFFIX = ".window.avro";
    private static final String SEALED_SUFFIX = ".sealed.avro";
    private static final String INVALID_SUFFIX = ".invalid";
    private static final String LOCK_FILE = ".lock";
    private static final String OF_TOPIC = " of topic ";
    private static final String CLOSING = "Closing the upload window" + OF_TOPIC;
    private static final String FAILED = " failed";
    /**
     * The digest kept for a generated id: none. A generated id is seen by no one before its segment is uploaded, so
     * a message given that id meanwhile is another message; it compares unequal to every digest.
     */
    private static final byte[] NOT_SEEN = new byte[0];

    private final AvroRawdataClient client;
    private final AvroStore store;
    private final String topic;
    private final AvroUploadWindow settings;
    private final Path folder;
    private final FileChannel lockChannel;
    private final FileLock folderLock;
    private final ReentrantLock appendLock; // the topic's publish lock in the client

    // guarded by appendLock
    private AvroRawdataClient.Tail tail; // the last segment this writer created or sealed
    private Window current;
    private final AvroTakenIds takenIds;

    // guarded by this
    private final Deque<Sealed> pending = new ArrayDeque<>();
    private long uploadedThrough = -1; // the last segment uploaded
    private boolean uploading;
    private RuntimeException failure;
    private boolean failureReported;

    private static final class Window {
        final Path file;
        final long segment;
        final FileOutputStream out;
        final DataFileWriter<GenericRecord> writer;
        final long openedNanos = System.nanoTime();
        UUID lastId;
        int count;
        long blockBytes;
        UUID firstId;
        long headerBytes; // where the first block starts
        long blockStart; // where the block being filled starts
        long previousBlockStart; // where the block before it starts
        long forcedThrough; // how much of the file is forced to disk
        boolean torn; // a write failed in the middle of a call: only the written blocks may be kept
        ScheduledFuture<?> timer;
        final Map<UUID, byte[]> digests = new HashMap<>(); // the window's ids, for the unique-id check (see NOT_SEEN)

        Window(Path file, long segment, UUID previousId, FileOutputStream out, DataFileWriter<GenericRecord> writer) {
            this.file = file;
            this.segment = segment;
            this.lastId = previousId;
            this.out = out;
            this.writer = writer;
        }
    }

    private record Sealed(Path file, long segment, UUID lastId, Map<UUID, byte[]> digests) {
    }

    private AvroTopicWriter(AvroRawdataClient client, String topic, AvroUploadWindow settings, Path folder,
                            FileChannel lockChannel, FileLock folderLock) {
        this.client = client;
        this.store = client.store();
        this.topic = topic;
        this.settings = settings;
        this.folder = folder;
        this.lockChannel = lockChannel;
        this.folderLock = folderLock;
        this.appendLock = client.publishLock(topic);
        this.takenIds = new AvroTakenIds(client, topic);
    }

    /**
     * Starts the writer of a topic: takes the folder's lock and recovers what a crash left. Called with the topic's
     * publish lock held.
     */
    static AvroTopicWriter start(AvroRawdataClient client, String topic, AvroUploadWindow settings) {
        Path folder = settings.localTempFolder().resolve(AvroTopicFolders.folderName(topic));
        FileChannel channel;
        try {
            Files.createDirectories(folder);
            channel = FileChannel.open(folder.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw storeFailure("Opening the buffer folder " + folder + OF_TOPIC + topic, e);
        }
        AvroTopicWriter writer;
        try {
            writer = new AvroTopicWriter(client, topic, settings, folder, channel, lock(channel, folder, topic));
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
        try {
            writer.recover();
            return writer;
        } catch (IOException e) {
            writer.release();
            throw storeFailure("Recovering the buffer" + OF_TOPIC + topic + " in " + folder, e);
        } catch (RuntimeException e) {
            writer.release();
            throw e;
        }
    }

    private static FileLock lock(FileChannel channel, Path folder, String topic) {
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null; // another client in this JVM
        } catch (IOException e) {
            throw storeFailure("Locking the buffer folder " + folder, e);
        }
        if (lock == null) {
            throw new RawdataException("The buffer folder " + folder + OF_TOPIC + "'" + topic
                    + "' is in use by another client: only one process may write a topic");
        }
        return lock;
    }

    // ---- publishing

    /**
     * Appends a call's messages to the open window, opening one if there is none. The call ends its block and writes
     * it to the file before it returns, so a block holds one call and a call that returned survives a crash of the
     * process.
     *
     * @param withIds gives the messages without an id an id after the given greatest id
     * @param given   the digests of the messages given with an id, by identity, computed before the lock
     */
    void publish(Function<UUID, List<RawdataMessage>> withIds, Map<RawdataMessage, byte[]> given) {
        appendLock.lock();
        try {
            throwIfFailed();
            if (current != null && ageSeconds(current) >= settings.maxSeconds()) {
                sealOrFail();
            }
            if (current == null) {
                openWindow();
            }
            Window window = current;
            List<RawdataMessage> stored = notTaken(withIds.apply(window.lastId), given, window);
            append(window, stored);
            for (RawdataMessage message : stored) {
                window.firstId = window.firstId == null ? message.id() : AvroSegments.lowest(window.firstId, message.id());
                window.lastId = AvroSegments.greatest(window.lastId, message.id());
                window.digests.put(message.id(), given.getOrDefault(message, NOT_SEEN));
            }
            window.count += stored.size();
            if (window.blockBytes > 0) {
                endBlock(window); // a call that returns is written to the file
            }
            if (window.blockStart >= settings.maxBytes()) {
                sealOrFail();
            }
        } catch (IOException e) {
            throw fail(storeFailure("Writing the upload window" + OF_TOPIC + topic, e));
        } finally {
            appendLock.unlock();
        }
    }

    /**
     * Leaves out the messages already in the topic: an id is unique within a topic, so the same message again is not
     * written twice, also within the call, and a different message under a taken id is refused, writing nothing. Only
     * an id at or below the topic's greatest id can be taken; it is looked up in the open window, the windows waiting
     * for upload and the uploaded segments.
     *
     * @throws RawdataDuplicateIdException if a message differs from the one under its id
     */
    private List<RawdataMessage> notTaken(List<RawdataMessage> messages, Map<RawdataMessage, byte[]> given, Window window) {
        Map<UUID, RawdataMessage> fresh = RawdataDuplicates.distinct(topic, messages);
        Iterator<RawdataMessage> each = fresh.values().iterator();
        while (each.hasNext()) {
            RawdataMessage message = each.next();
            if (RawdataIds.ORDER.compare(message.id(), window.lastId) > 0) {
                continue; // after the topic's greatest id: not taken
            }
            Optional<byte[]> taken = takenDigest(message.id(), window);
            if (taken.isPresent()) {
                if (!Arrays.equals(taken.get(), given.get(message))) { // only a given id can be at or below the greatest
                    throw new RawdataDuplicateIdException(topic, message.id());
                }
                each.remove(); // written before
            }
        }
        return fresh.size() == messages.size() ? messages : new ArrayList<>(fresh.values());
    }

    /**
     * The digest of the message under an id in the topic, if it is taken. A window is in {@code pending} until its
     * segment is uploaded, so a window that uploads meanwhile is found in one or the other. A generated id in a
     * waiting window may already be seen if its segment was uploaded just now: then the segment's digest counts.
     */
    private Optional<byte[]> takenDigest(UUID id, Window window) {
        byte[] digest = window.digests.get(id);
        if (digest != null) {
            return Optional.of(digest); // the open window is never seen
        }
        List<Sealed> waiting;
        long uploaded;
        synchronized (this) {
            waiting = new ArrayList<>(pending);
            uploaded = uploadedThrough;
        }
        for (Sealed sealed : waiting) {
            digest = sealed.digests().get(id);
            if (digest == NOT_SEEN) {
                return Optional.of(takenIds.findIn(sealed.segment(), id).orElse(NOT_SEEN));
            }
            if (digest != null) {
                return Optional.of(digest);
            }
        }
        return takenIds.find(id, uploaded);
    }

    private static void append(Window window, List<RawdataMessage> stored) throws IOException {
        try {
            for (RawdataMessage message : stored) {
                window.writer.append(AvroSegments.toRecord(message));
                window.blockBytes += AvroSegments.estimatedSize(message);
            }
        } catch (IOException | RuntimeException e) {
            window.torn = true; // part of the call is in Avro's block buffer, never written
            throw e;
        }
    }

    /**
     * Closes the open window and waits until every sealed window is uploaded.
     *
     * @throws RawdataException if an upload failed, or another process writes the topic
     */
    void flush() {
        flush(Long.MAX_VALUE);
    }

    /**
     * Closes the open window and waits, at most the given time, until every sealed window is uploaded. Uploads go on
     * after a timeout.
     *
     * @param timeoutNanos the longest to wait, {@code Long.MAX_VALUE} for no limit
     * @throws RawdataException if an upload failed, another process writes the topic, or the time ran out
     */
    void flush(long timeoutNanos) {
        appendLock.lock();
        try {
            throwIfFailed();
            if (current != null) {
                sealOrFail();
            }
        } catch (IOException e) {
            throw fail(storeFailure(CLOSING + topic, e));
        } finally {
            appendLock.unlock();
        }
        boolean uploaded = awaitUploads(timeoutNanos);
        throwIfFailed();
        if (!uploaded) {
            throw new RawdataException("Waiting for the uploads" + OF_TOPIC + topic + " timed out ("
                    + AvroUploadWindow.CLOSE_TIMEOUT_SECONDS + " = " + settings.closeTimeoutSeconds()
                    + " s); the windows not uploaded stay in " + folder);
        }
    }

    /**
     * @return false if the time ran out first
     */
    private synchronized boolean awaitUploads(long timeoutNanos) {
        long end = System.nanoTime() + Math.min(timeoutNanos, Long.MAX_VALUE / 2);
        while ((uploading || !pending.isEmpty()) && failure == null) {
            long left = end - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RawdataException("Interrupted while waiting for the upload" + OF_TOPIC + topic);
            }
        }
        return true;
    }

    /**
     * Uploads what is buffered, waits at most the given time, and releases the folder. The writer cannot be used
     * afterwards; when the time runs out, it stops uploading.
     *
     * @param timeoutNanos the longest to wait for the uploads
     * @throws RawdataException if an upload failed or the time ran out; the windows not uploaded stay on disk
     */
    void close(long timeoutNanos) {
        boolean reportedBefore;
        synchronized (this) {
            reportedBefore = failureReported;
        }
        try {
            flush(timeoutNanos);
        } catch (RawdataException e) {
            if (failureOrNull() == null) {
                fail(e); // timed out or interrupted: no further upload starts; the windows stay for the next writer
            }
            // A failure is thrown at close if it was never reported, or if data is left that was not uploaded.
            if (!reportedBefore || hasPending() || hasOpenData()) {
                throw e;
            }
        } finally {
            appendLock.lock();
            try {
                if (current != null) {
                    abandonOpenWindow(failureOrNull() instanceof RawdataWriterConflictException);
                }
            } finally {
                appendLock.unlock();
                release();
            }
        }
    }

    private synchronized boolean hasPending() {
        return !pending.isEmpty();
    }

    private boolean hasOpenData() {
        appendLock.lock();
        try {
            return current != null && current.count > 0;
        } finally {
            appendLock.unlock();
        }
    }

    private void openWindow() throws IOException {
        long segment = tail.segment() + 1;
        if (store.exists(topic, segment)) {
            throw fail(new RawdataWriterConflictException(topic)); // another process created it
        }
        Path file = folder.resolve(name(segment) + OPEN_SUFFIX);
        FileOutputStream out = new FileOutputStream(file.toFile());
        DataFileWriter<GenericRecord> writer = AvroSegments.writer(segment, tail.lastId());
        try {
            writer.setFlushOnEveryBlock(true);
            AvroSegments.create(writer, out, settings.syncInterval()); // Avro never ends a block by itself; endBlock does
            out.getFD().sync(); // the header, so a crash right after leaves a readable window
        } catch (IOException | RuntimeException e) {
            out.close();
            Files.deleteIfExists(file);
            throw e;
        }
        Window window = new Window(file, segment, tail.lastId(), out, writer);
        window.blockStart = writer.sync(); // the end of the header
        window.headerBytes = window.blockStart;
        window.forcedThrough = window.blockStart;
        current = window;
        // The timer hands the close to the executor: a topic whose uploads are slow never holds up another's timer.
        window.timer = client.scheduler().schedule(() -> client.executor().execute(() -> closeIfStill(window)),
                settings.maxSeconds(), TimeUnit.SECONDS);
    }

    /**
     * The timer: closes the window when its time is up, if it is still the open one.
     */
    private void closeIfStill(Window window) {
        appendLock.lock();
        try {
            if (current == window && failureOrNull() == null && !window.torn) {
                sealOrFail();
            }
        } catch (IOException e) {
            fail(storeFailure(CLOSING + topic, e));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, CLOSING + topic + FAILED, e); // the writer is failed
        } finally {
            appendLock.unlock();
        }
    }

    /**
     * Writes the block being filled to the file, so the calls in it survive a crash of the process, and forces the file
     * to disk once {@code syncInterval} bytes are written since it was last forced, so a crash of the machine loses the
     * calls written since: about that much, more after a larger call. Never through the file's channel: an interrupted caller would close it.
     */
    private void endBlock(Window window) throws IOException {
        window.previousBlockStart = window.blockStart;
        window.blockStart = window.writer.sync(); // the file's length: every block is written whole
        window.blockBytes = 0;
        if (window.blockStart - window.forcedThrough >= settings.syncInterval()) {
            window.out.getFD().sync();
            window.forcedThrough = window.blockStart;
        }
    }

    /**
     * Writes the last block, closes the file and fills in its summary in the header, forcing it to disk: the window
     * as it is uploaded.
     */
    private static void finish(Window window) throws IOException {
        long lastBlock = window.blockBytes > 0 ? window.blockStart : window.previousBlockStart;
        window.writer.close(); // writes the block being filled
        if (window.count > 0) {
            AvroSegments.fillSummary(window.file,
                    new AvroSegments.Summary(window.firstId, window.lastId, window.count, lastBlock), window.headerBytes);
        }
    }

    /**
     * Seals the open window, failing the writer if that fails in any way: the window is no longer the open one, and
     * the next must not open in its place.
     */
    private void sealOrFail() throws IOException {
        try {
            seal();
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /**
     * Closes the open window: forces it to disk, seals it and queues it for upload; an empty one is removed.
     * Called with the publish lock held; waits while too many windows wait for upload.
     */
    private void seal() throws IOException {
        Window window = current;
        current = null;
        if (window.timer != null) {
            window.timer.cancel(false);
        }
        finish(window);
        if (window.count == 0) {
            Files.deleteIfExists(window.file);
            return;
        }
        Path sealed = folder.resolve(name(window.segment) + SEALED_SUFFIX);
        Files.move(window.file, sealed, StandardCopyOption.ATOMIC_MOVE);
        tail = new AvroRawdataClient.Tail(window.segment, window.lastId);
        enqueue(new Sealed(sealed, window.segment, window.lastId, window.digests));
    }

    private synchronized void enqueue(Sealed sealed) {
        while (pending.size() >= settings.maxPendingUploads() && failure == null) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // queued anyway: the window is sealed on disk and must not be lost
            }
        }
        if (failure instanceof RawdataWriterConflictException) {
            invalidate(sealed.file()); // sealed after another writer was found: set aside like the rest
            return;
        }
        pending.addLast(sealed); // kept even when the writer failed, so it stays on disk for recovery
        if (failure == null && !uploading) {
            uploading = true;
            client.executor().execute(this::drain);
        }
    }

    /**
     * Uploads the sealed windows one at a time, in order, until none is left or one fails.
     */
    private void drain() {
        for (; ; ) {
            Sealed next;
            synchronized (this) {
                if (pending.isEmpty() || failure != null) {
                    uploading = false;
                    notifyAll();
                    return;
                }
                next = pending.peekFirst();
            }
            try {
                if (!upload(next)) {
                    fail(new RawdataWriterConflictException(topic));
                    return;
                }
                if (failureOrNull() != null) {
                    // The upload outlived a close that timed out, and the folder may have a new writer: leave the
                    // file to its recovery, which finds the segment stored with the same bytes and removes it.
                    return;
                }
                Files.delete(next.file());
            } catch (IOException e) {
                fail(storeFailure("Uploading segment " + next.segment() + OF_TOPIC + topic, e));
                return;
            } catch (RuntimeException e) {
                fail(e);
                return;
            }
            synchronized (this) {
                pending.pollFirst();
                uploadedThrough = next.segment();
                notifyAll();
            }
            client.uploaded(topic, new AvroRawdataClient.Tail(next.segment(), next.lastId()));
        }
    }

    /**
     * Creates a sealed window's segment, as one of the client's uploads at once ({@link AvroUploadLimiter}).
     *
     * @return false if the segment was taken
     */
    private boolean upload(Sealed sealed) throws IOException {
        AvroUploadLimiter limiter = client.limiter();
        boolean atLimit;
        try {
            atLimit = limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to upload segment " + sealed.segment());
        }
        boolean created = false;
        try {
            created = store.create(topic, sealed.segment(), Files.readAllBytes(sealed.file()));
            return created;
        } finally {
            limiter.release(created, atLimit);
        }
    }

    /**
     * Fails the writer: every later call throws. On a conflict the windows not uploaded are set aside, before anyone
     * waiting is woken, so a flush or close that returns sees them set aside.
     */
    private RuntimeException fail(RuntimeException cause) {
        synchronized (this) {
            if (failure == null) {
                failure = cause;
                if (cause instanceof RawdataWriterConflictException) {
                    for (Sealed sealed : pending) {
                        invalidate(sealed.file());
                    }
                    pending.clear();
                }
            }
            uploading = false;
            notifyAll();
        }
        if (cause instanceof RawdataWriterConflictException) {
            LOG.log(System.Logger.Level.ERROR, "Another process writes topic " + topic
                    + "; this client stops writing it. Windows not uploaded are kept as *" + INVALID_SUFFIX + " in " + folder);
        } else {
            LOG.log(System.Logger.Level.ERROR, "The writer" + OF_TOPIC + topic + FAILED + "; windows not uploaded stay in "
                    + folder + " and are uploaded when a writer of the topic starts again", cause);
        }
        return cause;
    }

    /**
     * A store or disk failure, with its cause, also while the client closes.
     */
    private static RawdataException storeFailure(String what, IOException e) {
        return new RawdataException(what + FAILED + ": " + e.getMessage(), e);
    }

    private synchronized RuntimeException failureOrNull() {
        return failure;
    }

    private void throwIfFailed() {
        RuntimeException cause;
        synchronized (this) {
            cause = failure;
            if (cause != null) {
                failureReported = true;
            }
        }
        if (cause instanceof RawdataWriterConflictException) {
            throw new RawdataWriterConflictException(topic);
        }
        if (cause != null) {
            throw new RawdataException("The writer" + OF_TOPIC + topic + FAILED + ": " + cause.getMessage(), cause);
        }
    }

    /**
     * Closes the open window of a failed writer. On a conflict it is set aside. A window torn by a failed write is
     * left as a crash leaves it, its written blocks on disk, for recovery to cut back. Otherwise it is forced to disk
     * and sealed, for the next writer to upload.
     */
    private void abandonOpenWindow(boolean conflict) {
        Window window = current;
        current = null;
        if (window.timer != null) {
            window.timer.cancel(false);
        }
        try {
            if (window.torn) {
                window.out.close(); // drops Avro's unwritten block: only whole calls stay on disk
            } else {
                finish(window);
            }
            if (window.count == 0 && !window.torn) {
                Files.deleteIfExists(window.file);
            } else if (conflict) {
                invalidate(window.file);
            } else if (!window.torn) {
                Files.move(window.file, folder.resolve(name(window.segment) + SEALED_SUFFIX), StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Closing the window file " + window.file + FAILED, e);
        }
    }

    private void release() {
        try {
            if (folderLock.isValid()) {
                folderLock.release();
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Releasing the lock of " + folder + FAILED, e);
        }
        closeQuietly(lockChannel);
    }

    // ---- recovery

    private void recover() throws IOException {
        tail = client.tail(topic);
        List<Path> leftovers;
        try (Stream<Path> files = Files.list(folder)) {
            leftovers = files.filter(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(OPEN_SUFFIX) || name.endsWith(SEALED_SUFFIX);
            }).sorted().toList(); // the name starts with the segment number in 19 digits
        }
        boolean conflict = false;
        for (Path file : leftovers) {
            if (conflict) {
                invalidate(file);
            } else {
                conflict = !recover(file);
            }
        }
        if (conflict) {
            throw new RawdataWriterConflictException(topic);
        }
        synchronized (this) {
            uploadedThrough = tail.segment();
        }
    }

    /**
     * Uploads or removes one leftover window.
     *
     * @return false if it does not belong after the topic's end, and was set aside
     */
    private boolean recover(Path file) throws IOException {
        boolean sealed = file.getFileName().toString().endsWith(SEALED_SUFFIX);
        AvroSegments.Leftover leftover = AvroSegments.readLeftover(file);
        if (leftover == null || leftover.messages().isEmpty()) {
            Files.delete(file); // nothing was appended, or nothing of it reached the disk
            return true;
        }
        byte[] content = sealed && leftover.complete()
                ? Files.readAllBytes(file)
                : AvroSegments.encode(leftover.messages(), leftover.segment(), leftover.previousId(), settings.syncInterval());
        UUID lastId = leftover.previousId();
        for (RawdataMessage message : leftover.messages()) {
            lastId = AvroSegments.greatest(lastId, message.id());
        }
        if (leftover.segment() <= tail.segment()) {
            Optional<byte[]> stored = store.read(topic, leftover.segment());
            if (sealed && stored.isPresent() && Arrays.equals(stored.get(), content)) {
                Files.delete(file); // uploaded before the crash; only its removal was lost
                return true;
            }
        } else if (leftover.segment() == tail.segment() + 1 && leftover.previousId().equals(tail.lastId())
                && store.create(topic, leftover.segment(), content)) {
            LOG.log(System.Logger.Level.INFO, "Uploaded segment " + leftover.segment() + OF_TOPIC + topic
                    + " that a crash left in " + file + " (" + leftover.messages().size() + " messages"
                    + (leftover.complete() ? "" : ", a torn last block left out") + ")");
            Files.delete(file);
            tail = new AvroRawdataClient.Tail(leftover.segment(), lastId);
            client.uploaded(topic, tail);
            return true;
        }
        LOG.log(System.Logger.Level.ERROR, "The window " + file + OF_TOPIC + topic + " that a crash left does not "
                + "follow the topic's end (another process wrote the topic); it is kept as " + file.getFileName() + INVALID_SUFFIX);
        invalidate(file);
        return false;
    }

    private void invalidate(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + INVALID_SUFFIX), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Setting aside " + file + FAILED, e);
        }
    }

    // ---- helpers

    private static long ageSeconds(Window window) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - window.openedNanos);
    }

    private static String name(long segment) {
        return String.format("%019d", segment);
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // nothing more to do
        }
    }

    @Override
    public String toString() {
        return "AvroTopicWriter{topic=" + topic + ", folder=" + folder + '}';
    }
}
