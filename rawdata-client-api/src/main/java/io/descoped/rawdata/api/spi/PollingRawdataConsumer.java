package io.descoped.rawdata.api.spi;

import io.descoped.rawdata.api.RawdataClosedException;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A consumer for providers that read their store in batches and poll it for new messages: the receive loop, waking,
 * asynchronous receive and closing, the same in every such provider. A provider says how to read the next batch
 * ({@link #fill}) and how to move to a time ({@link #reposition}).
 * <p>
 * One receive or seek runs at a time; others, and asynchronous receives, wait their turn. A receive that finds
 * nothing waits for the poll interval, or until {@link #wake()}: a publish through the same client, or a close. The
 * wait does not hold the turn, and a wake-up that comes while the receive reads is not lost.
 */
public abstract class PollingRawdataConsumer implements RawdataConsumer {

    private final String topic;
    private final long pollIntervalNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong wakeUps = new AtomicLong();
    private final Object signal = new Object();
    private final ReentrantLock turn = new ReentrantLock();
    private final Deque<RawdataMessage> buffer = new ArrayDeque<>(); // guarded by turn
    private final AtomicReference<CompletableFuture<RawdataMessage>> pending = new AtomicReference<>(); // receiveAsync

    /**
     * Creates the consumer.
     *
     * @param topic              the topic
     * @param pollIntervalMillis how long a receive that finds nothing waits before it reads again, 1 or more
     */
    protected PollingRawdataConsumer(String topic, long pollIntervalMillis) {
        if (pollIntervalMillis < 1) {
            throw new IllegalArgumentException("The poll interval must be 1 ms or more: " + pollIntervalMillis);
        }
        this.topic = topic;
        this.pollIntervalNanos = TimeUnit.MILLISECONDS.toNanos(pollIntervalMillis);
    }

    /**
     * Reads the messages after the last one read, if there are any, into the buffer, in order. Called with the turn
     * held, when the buffer is empty. If it throws, the messages it put in the buffer are kept and delivered, and the
     * read position must not have moved past any message it did not put there, so that the next read finds it.
     *
     * @param buffer where the messages go
     */
    protected abstract void fill(Deque<RawdataMessage> buffer);

    /**
     * Moves to the first message written at or after a time. Called with the turn held, after the buffer is cleared.
     *
     * @param timestamp the time, milliseconds since 1970-01-01T00:00Z
     */
    protected abstract void reposition(long timestamp);

    /**
     * Whether the client that created this consumer is closed.
     *
     * @return true if it is
     */
    protected abstract boolean isClientClosed();

    /**
     * The executor of asynchronous receives: the client's, which fails its tasks when the client closes.
     *
     * @return the executor
     */
    protected abstract Executor executor();

    /**
     * Called once, when the consumer closes, before waiting receives are woken; for example, to tell the client.
     */
    protected void closed() {
        // nothing by default
    }

    @Override
    public final String topic() {
        return topic;
    }

    @Override
    public final RawdataMessage receive(int timeout, TimeUnit unit) throws InterruptedException, RawdataClosedException {
        checkOpen();
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        turn.lockInterruptibly();
        try {
            for (; ; ) {
                checkOpen();
                long wakeUpsBefore = wakeUps.get();
                RawdataMessage message = next();
                if (message != null) {
                    return message;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                turn.unlock();
                try {
                    await(wakeUpsBefore, Math.min(remaining, pollIntervalNanos));
                } finally {
                    turn.lock();
                }
            }
        } finally {
            turn.unlock();
        }
    }

    private RawdataMessage next() {
        if (buffer.isEmpty()) {
            try {
                fill(buffer);
            } catch (RuntimeException e) {
                if (isClosed()) {
                    throw closedException(); // the client closed its store during the read
                }
                throw e;
            }
        }
        return buffer.pollFirst();
    }

    private void await(long wakeUpsBefore, long nanos) throws InterruptedException {
        synchronized (signal) {
            if (wakeUps.get() == wakeUpsBefore && !isClosed()) {
                TimeUnit.NANOSECONDS.timedWait(signal, nanos);
            }
        }
    }

    /**
     * Wakes receives that wait, to read again or to see that the consumer or its client is closed. Never blocks.
     */
    public final void wake() {
        wakeUps.incrementAndGet();
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The future is completed while this consumer's turn is held, so a message taken for a future that was cancelled
     * or timed out meanwhile goes back to the head of the buffer, and the next receive gets it. A dependent that is
     * not async runs then too, before the next receive of this consumer: give long work to an async dependent, and
     * never wait in one for another receive of this consumer (that receive needs the turn). A cancelled or timed-out
     * receive stops waiting at once and frees its thread; closing the consumer fails it.
     */
    @Override
    public final CompletableFuture<RawdataMessage> receiveAsync() {
        checkOpen();
        CompletableFuture<RawdataMessage> result = new CompletableFuture<>();
        result.whenComplete((message, failure) -> wake()); // a cancelled or timed-out receive stops waiting
        pending.set(result); // failed by close, also before its task runs
        result.whenComplete((message, failure) -> pending.compareAndSet(result, null)); // holds no message once done
        if (isClosed()) {
            result.completeExceptionally(closedException()); // closed meanwhile
            return result;
        }
        try {
            executor().execute(() -> receiveInto(result));
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(closedException()); // the client closed meanwhile
        }
        return result;
    }

    private void receiveInto(CompletableFuture<RawdataMessage> result) {
        try {
            turn.lockInterruptibly();
            try {
                for (; ; ) {
                    long wakeUpsBefore = wakeUps.get(); // before the check: a cancel after it wakes the wait
                    if (result.isDone()) {
                        return;
                    }
                    checkOpen();
                    RawdataMessage message = next();
                    if (message != null) {
                        if (!result.complete(message)) {
                            buffer.addFirst(message); // cancelled or timed out meanwhile: kept for the next receive
                        }
                        return;
                    }
                    turn.unlock();
                    try {
                        await(wakeUpsBefore, pollIntervalNanos);
                    } finally {
                        turn.lock();
                    }
                }
            } finally {
                turn.unlock();
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
    public final void seek(long timestamp) {
        checkOpen();
        turn.lock();
        try {
            buffer.clear();
            reposition(timestamp);
        } finally {
            turn.unlock();
        }
    }

    @Override
    public final boolean isClosed() {
        return closed.get() || isClientClosed();
    }

    @Override
    public final void close() {
        if (closed.compareAndSet(false, true)) {
            closed();
            CompletableFuture<RawdataMessage> outstanding = pending.getAndSet(null);
            if (outstanding != null) {
                outstanding.completeExceptionally(closedException()); // also one whose task never ran
            }
            wake();
        }
    }

    private void checkOpen() {
        if (isClosed()) {
            throw closedException();
        }
    }

    private RawdataClosedException closedException() {
        return new RawdataClosedException("consumer of topic " + topic + " is closed");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{topic=" + topic + ", closed=" + isClosed() + '}';
    }
}
