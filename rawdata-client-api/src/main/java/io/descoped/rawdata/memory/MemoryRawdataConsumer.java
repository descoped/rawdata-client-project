package io.descoped.rawdata.memory;

import io.descoped.rawdata.api.RawdataClosedException;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataIds;
import io.descoped.rawdata.api.RawdataMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

final class MemoryRawdataConsumer implements RawdataConsumer {

    private final MemoryRawdataClient client;
    private final MemoryRawdataTopic topic;
    private final AtomicReference<MemoryCursor> position;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock turn = new ReentrantLock(); // one take at a time: read the next message, move past it
    private final AtomicReference<CompletableFuture<RawdataMessage>> pending = new AtomicReference<>(); // receiveAsync

    MemoryRawdataConsumer(MemoryRawdataClient client, MemoryRawdataTopic topic, MemoryCursor start) {
        this.client = client;
        this.topic = topic;
        this.position = new AtomicReference<>(start != null ? start : new MemoryCursor(RawdataIds.beginningOfTime(), true));
    }

    @Override
    public String topic() {
        return topic.name;
    }

    @Override
    public RawdataMessage receive(int timeout, TimeUnit unit) throws InterruptedException, RawdataClosedException {
        checkOpen();
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (; ; ) {
            RawdataMessage message = take(null);
            if (message != null) {
                return message;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            topic.next(position.get(), remaining, this::isClosed); // waits for a message, without the turn
            checkOpen();
        }
    }

    /**
     * Takes the message after the position, if there is one, and moves the position past it. Given a future, the
     * position moves only if the future takes the message: one cancelled or timed out meanwhile leaves it for the
     * next receive.
     */
    private RawdataMessage take(CompletableFuture<RawdataMessage> into) throws InterruptedException {
        turn.lock();
        try {
            checkOpen();
            MemoryCursor cursor = position.get();
            RawdataMessage message = topic.next(cursor, 0, this::isClosed);
            if (message != null && (into == null || into.complete(message))) {
                position.compareAndSet(cursor, new MemoryCursor(message.id(), false)); // a seek meanwhile wins
            }
            return message;
        } finally {
            turn.unlock();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The future is completed while this consumer's turn is held, so a message for a future that was cancelled or
     * timed out meanwhile stays for the next receive. A dependent that is not async runs then too: give long work to
     * an async dependent, and never wait in one for another receive of this consumer. A cancelled or timed-out
     * receive stops waiting at once and frees its thread; closing the consumer fails it.
     */
    @Override
    public CompletableFuture<RawdataMessage> receiveAsync() {
        checkOpen();
        CompletableFuture<RawdataMessage> result = new CompletableFuture<>();
        result.whenComplete((message, failure) -> topic.wakeUp()); // a cancelled or timed-out receive stops waiting
        pending.set(result); // failed by close, also before its task runs
        if (isClosed()) {
            result.completeExceptionally(closedException()); // closed meanwhile
            return result;
        }
        try {
            client.executor().execute(() -> receiveInto(result));
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(closedException()); // the client closed meanwhile
        }
        return result;
    }

    private void receiveInto(CompletableFuture<RawdataMessage> result) {
        try {
            while (!result.isDone()) {
                if (take(result) == null) {
                    topic.next(position.get(), TimeUnit.SECONDS.toNanos(1), () -> isClosed() || result.isDone());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // closing the client interrupts its threads
            result.completeExceptionally(isClosed() ? closedException() : e);
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        } catch (Error e) { // NOSONAR: the task's entry point; the caller's future gets the Error, which is rethrown
            result.completeExceptionally(e);
            throw e;
        }
    }

    @Override
    public void seek(long timestamp) {
        checkOpen();
        position.set(new MemoryCursor(RawdataIds.beginningOf(timestamp), true));
    }

    @Override
    public boolean isClosed() {
        return closed.get() || client.isClosed();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.removed(this);
            CompletableFuture<RawdataMessage> outstanding = pending.getAndSet(null);
            if (outstanding != null) {
                outstanding.completeExceptionally(closedException()); // also one whose task never ran
            }
            topic.wakeUp();
        }
    }

    private void checkOpen() {
        if (isClosed()) {
            throw closedException();
        }
    }

    private RawdataClosedException closedException() {
        return new RawdataClosedException("consumer of topic " + topic.name + " is closed");
    }

    @Override
    public String toString() {
        return "MemoryRawdataConsumer{topic=" + topic.name + ", position=" + position.get() + ", closed=" + isClosed() + '}';
    }
}
