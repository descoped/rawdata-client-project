package io.descoped.rawdata.provider.avro;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * The store as an application's thread calls it, through the client: a store call is not failed by an interrupt
 * already pending when it starts, as in postgres, whose JDBC driver does not look at the flag. A file channel, the GCS
 * HTTP client and the AWS SDK all fail at once on a thread whose flag is set; here the flag is cleared for the call and
 * set again after it, so the application still sees it. An interrupt that arrives during the call stops it as before.
 * <p>
 * The client's waits on an application's thread, for uploads ({@code flush}, {@code close}) and for room among the
 * pending uploads ({@code publish}), go through {@link #call} the same way. The client's own threads
 * ({@link #thread}), which upload windows and close them on time, are left as they are: closing the client interrupts
 * them to stop uploads that outlast {@code avro-file.close-timeout-seconds}.
 */
final class AvroCallerStore implements AvroStore {

    private final AvroStore store;

    AvroCallerStore(AvroStore store) {
        this.store = store;
    }

    /**
     * A thread of the client: its store calls see its interrupt flag.
     *
     * @param task what it runs
     * @param name its name
     * @return the thread, a daemon
     */
    static Thread thread(Runnable task, String name) {
        Thread thread = new ClientThread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class ClientThread extends Thread {
        ClientThread(Runnable task, String name) {
            super(task, name);
        }
    }

    /**
     * A store call, or a wait of the client, made on the caller's thread.
     */
    @FunctionalInterface
    interface Call<T, E extends Exception> {
        T call() throws E;
    }

    /**
     * Makes a call on the current thread. On an application's thread, a pending interrupt flag is cleared for the call
     * and set again after it; on the client's own threads, the call sees the flag.
     *
     * @param call the call
     * @return what it returns
     * @throws E what it throws
     */
    static <T, E extends Exception> T call(Call<T, E> call) throws E {
        if (Thread.currentThread() instanceof ClientThread) {
            return call.call();
        }
        boolean interrupted = Thread.interrupted();
        try {
            return call.call();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean create(String topic, long segment, byte[] content) throws IOException {
        return call(() -> store.create(topic, segment, content));
    }

    @Override
    public Optional<Segment> open(String topic, long segment) throws IOException {
        return call(() -> store.open(topic, segment)).map(CallerSegment::new);
    }

    private record CallerSegment(Segment segment) implements Segment {
        @Override
        public long size() {
            return segment.size();
        }

        @Override
        public byte[] read(long offset, int length) throws IOException {
            return call(() -> segment.read(offset, length));
        }
    }

    @Override
    public boolean exists(String topic, long segment) throws IOException {
        return call(() -> store.exists(topic, segment));
    }

    @Override
    public Set<String> metadataNames(String topic) throws IOException {
        return call(() -> store.metadataNames(topic));
    }

    @Override
    public Optional<byte[]> readMetadata(String topic, String name) throws IOException {
        return call(() -> store.readMetadata(topic, name));
    }

    @Override
    public void writeMetadata(String topic, String name, byte[] value) throws IOException {
        call(() -> {
            store.writeMetadata(topic, name, value);
            return null;
        });
    }

    @Override
    public void deleteMetadata(String topic, String name) throws IOException {
        call(() -> {
            store.deleteMetadata(topic, name);
            return null;
        });
    }

    @Override
    public void onThrottled(Runnable listener) {
        store.onThrottled(listener);
    }

    @Override
    public void close() {
        store.close();
    }

    @Override
    public String toString() {
        return store.toString();
    }
}
