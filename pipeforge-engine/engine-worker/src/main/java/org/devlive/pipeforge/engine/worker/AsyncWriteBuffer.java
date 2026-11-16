/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * Lets a sink write on its own thread so that reading and writing overlap.
 *
 * <pre>
 * task thread: … → batch ──► bounded queue (capacity batches) ──► write thread: SinkWriter
 *                       blocks when full (backpressure)        Batch / Barrier / End
 * </pre>
 *
 * <p>The write thread handles the queue in order: a batch is written; a barrier means "everything
 * before me is written": the writer flushes and the barrier completes; the end stops the thread.
 * The time the task thread blocks on a full queue and the time the write thread waits on an empty
 * one tell whether the source or the target is the bottleneck.
 *
 * <p>The rows waiting to be written are accounted by their estimated size; once they reach the
 * memory limit of the task, the task thread waits until the write thread has written some, as it
 * waits for a full queue. A row is always taken when nothing waits, so a single row larger than
 * the limit still passes.
 *
 * <p>When the writer fails, the queue is cleared and the failure is rethrown on the task thread at
 * its next call, so a full queue never blocks it forever.
 */
final class AsyncWriteBuffer implements AutoCloseable {

    /** What the write thread does with the items: {@code Row}s and schema change events in order. */
    interface Target {
        void write(Object item);

        void flush();
    }

    private sealed interface Element {}

    private record Batch(List<Object> items, long bytes) implements Element {}

    private record Barrier(CompletableFuture<Void> written) implements Element {}

    private record End() implements Element {}

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final Target target;
    private final int batchSize;
    private final BlockingQueue<Element> queue;
    private final Thread thread;
    /** The log tags of the task, so that the write thread logs into the task's log. */
    private final java.util.@Nullable Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();

    private final AtomicLong producerWaitNanos = new AtomicLong();
    /** When the task thread started its current wait, 0 when it does not wait. */
    private volatile long waitingSince;

    private final AtomicLong writerWaitNanos = new AtomicLong();
    private final AtomicLong busyNanos = new AtomicLong();
    private volatile @Nullable PipeForgeException failure;
    private List<Object> current;
    private long currentBytes;
    private final long maxBytes;
    /** Bytes of the items not written yet, in the current batch and in the queue. */
    private final AtomicLong accounted = new AtomicLong();

    private final Object room = new Object();

    AsyncWriteBuffer(String name, Target target, int batchSize, int capacity, ClassLoader loader) {
        this(name, target, batchSize, capacity, Long.MAX_VALUE, loader);
    }

    /** @param maxBytes the bytes of unwritten items at which the task thread waits */
    AsyncWriteBuffer(String name, Target target, int batchSize, int capacity, long maxBytes, ClassLoader loader) {
        this.maxBytes = maxBytes;
        this.target = target;
        this.batchSize = batchSize;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.current = new ArrayList<>(batchSize);
        this.thread = new Thread(this::loop, name);
        thread.setDaemon(true);
        thread.setContextClassLoader(loader);
        thread.start();
    }

    /** Adds a row or schema change; blocks while the queue is full. */
    void add(Object item) {
        add(item, 0);
    }

    /**
     * Adds a row or schema change of {@code bytes}; blocks while the queue is full or the unwritten
     * items reach the memory limit.
     */
    void add(Object item, long bytes) {
        if (bytes > 0 && full(bytes)) {
            // what waits in the current batch must reach the writer, or nothing frees room
            handOver();
            awaitRoom(bytes);
        }
        current.add(item);
        currentBytes += bytes;
        accounted.addAndGet(bytes);
        if (current.size() >= batchSize) {
            handOver();
        }
    }

    private boolean full(long bytes) {
        long held = accounted.get();
        return held > 0 && held + bytes > maxBytes;
    }

    private void awaitRoom(long bytes) {
        long start = startWaiting();
        try {
            synchronized (room) {
                while (full(bytes)) {
                    checkFailure();
                    room.wait(TimeUnit.NANOSECONDS.toMillis(POLL_NANOS));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the writer", e);
        } finally {
            stopWaiting(start);
        }
    }

    private long startWaiting() {
        long start = System.nanoTime();
        waitingSince = start;
        return start;
    }

    private void stopWaiting(long start) {
        waitingSince = 0;
        producerWaitNanos.addAndGet(System.nanoTime() - start);
    }

    /** Bytes of the rows not written yet; callable from any thread. */
    long accountedBytes() {
        return accounted.get();
    }

    /** Hands over the pending rows and waits until the writer has written and flushed everything. */
    void flush() {
        handOver();
        CompletableFuture<Void> written = new CompletableFuture<>();
        enqueue(new Barrier(written));
        while (true) {
            checkFailure();
            try {
                written.get(POLL_NANOS, TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException e) {
                // check the writer again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the writer", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("The barrier never fails", e);
            }
        }
    }

    /** Hands over the pending rows, stops the write thread once it has written them and waits for it. */
    void finish() {
        handOver();
        enqueue(new End());
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the writer", e);
        }
        checkFailure();
    }

    /** Interrupts the write thread, e.g. when the task is cancelled. */
    void interrupt() {
        thread.interrupt();
    }

    /** Stops the write thread without writing what is left, and drops what is left. */
    @Override
    public void close() {
        if (thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!thread.isAlive()) {
            queue.clear();
            current = new ArrayList<>(batchSize);
            currentBytes = 0;
            accounted.set(0);
        }
    }

    /**
     * Time the task thread spent blocked on a full queue or on the memory limit, including a wait
     * that has not ended, so that a reader blocked for long shows it while it waits.
     */
    long producerWaitNanos() {
        long since = waitingSince;
        return producerWaitNanos.get() + (since == 0 ? 0 : Math.max(0, System.nanoTime() - since));
    }

    /** Batches in the queue now; callable from any thread. */
    int depth() {
        return queue.size();
    }

    /** The batches the queue holds. */
    int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    /** Time the write thread spent writing and flushing. */
    long busyNanos() {
        return busyNanos.get();
    }

    /** Time the write thread spent waiting on an empty queue. */
    long writerWaitNanos() {
        return writerWaitNanos.get();
    }

    private void handOver() {
        if (!current.isEmpty()) {
            enqueue(new Batch(current, currentBytes));
            current = new ArrayList<>(batchSize);
            currentBytes = 0;
        }
    }

    private void enqueue(Element element) {
        checkFailure();
        if (queue.offer(element)) {
            return;
        }
        long start = startWaiting();
        try {
            while (!queue.offer(element, POLL_NANOS, TimeUnit.NANOSECONDS)) {
                checkFailure();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the writer", e);
        } finally {
            stopWaiting(start);
        }
    }

    private void checkFailure() {
        PipeForgeException error = failure;
        if (error != null) {
            throw error;
        }
    }

    private void loop() {
        if (logContext != null) {
            org.slf4j.MDC.setContextMap(logContext);
        }
        try {
            while (true) {
                Element element = queue.poll();
                if (element == null) {
                    long start = System.nanoTime();
                    element = queue.take();
                    writerWaitNanos.addAndGet(System.nanoTime() - start);
                }
                long start = System.nanoTime();
                switch (element) {
                    case Batch batch -> {
                        batch.items().forEach(target::write);
                        busyNanos.addAndGet(System.nanoTime() - start);
                        accounted.addAndGet(-batch.bytes());
                        synchronized (room) {
                            room.notifyAll();
                        }
                    }
                    case Barrier barrier -> {
                        target.flush();
                        busyNanos.addAndGet(System.nanoTime() - start);
                        barrier.written().complete(null);
                    }
                    case End end -> {
                        return;
                    }
                }
            }
        } catch (PipeForgeException e) {
            fail(e);
        } catch (InterruptedException e) {
            // Only on cancel or close: the task thread no longer waits for the result.
            fail(PipeForgeException.wrap(e));
        } catch (RuntimeException e) {
            fail(PipeForgeException.wrap(e));
        }
    }

    private void fail(PipeForgeException error) {
        failure = error;
        queue.clear();
    }
}
