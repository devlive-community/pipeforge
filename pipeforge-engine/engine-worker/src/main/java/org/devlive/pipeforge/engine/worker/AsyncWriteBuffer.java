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

    private record Batch(List<Object> items) implements Element {}

    private record Barrier(CompletableFuture<Void> written) implements Element {}

    private record End() implements Element {}

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final Target target;
    private final int batchSize;
    private final BlockingQueue<Element> queue;
    private final Thread thread;
    private final AtomicLong producerWaitNanos = new AtomicLong();
    private final AtomicLong writerWaitNanos = new AtomicLong();
    private final AtomicLong busyNanos = new AtomicLong();
    private volatile @Nullable PipeForgeException failure;
    private List<Object> current;

    AsyncWriteBuffer(String name, Target target, int batchSize, int capacity, ClassLoader loader) {
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
        current.add(item);
        if (current.size() >= batchSize) {
            enqueue(new Batch(current));
            current = new ArrayList<>(batchSize);
        }
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

    /** Stops the write thread without writing what is left. */
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
    }

    /** Time the task thread spent blocked on a full queue. */
    long producerWaitNanos() {
        return producerWaitNanos.get();
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
            enqueue(new Batch(current));
            current = new ArrayList<>(batchSize);
        }
    }

    private void enqueue(Element element) {
        checkFailure();
        if (queue.offer(element)) {
            return;
        }
        long start = System.nanoTime();
        try {
            while (!queue.offer(element, POLL_NANOS, TimeUnit.NANOSECONDS)) {
                checkFailure();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the writer", e);
        } finally {
            producerWaitNanos.addAndGet(System.nanoTime() - start);
        }
    }

    private void checkFailure() {
        PipeForgeException error = failure;
        if (error != null) {
            throw error;
        }
    }

    private void loop() {
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
