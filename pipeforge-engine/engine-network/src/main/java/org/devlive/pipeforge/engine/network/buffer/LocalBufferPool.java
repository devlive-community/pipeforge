/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.buffer;

import java.time.Duration;
import java.util.function.Supplier;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * The buffers of one output or input: {@code min} are reserved in the network pool, up to {@code
 * max} are taken when the network pool has them free. Taking a buffer when all are in use waits
 * for one to be recycled — the backpressure of a task.
 */
public final class LocalBufferPool {

    private final NetworkBufferPool network;
    private final String owner;
    private final int min;
    private final int max;
    private int reserved;
    private int inUse;
    private boolean destroyed;
    private @Nullable PipeForgeException failure;
    private long waitNanos;
    /** When the current wait for a buffer started, or 0. */
    private long waitingSince;

    LocalBufferPool(NetworkBufferPool network, String owner, int min, int max) {
        this.network = network;
        this.owner = owner;
        this.min = min;
        this.max = Math.max(min, max);
        this.reserved = min;
    }

    public String owner() {
        return owner;
    }

    /** A buffer if one is free now, otherwise {@code null}. */
    public synchronized @Nullable Buffer poll() {
        check();
        if (inUse >= reserved) {
            if (reserved >= max || !network.tryReserve()) {
                return null;
            }
            reserved++;
        }
        inUse++;
        return new Buffer(network.memory(), this::recycle);
    }

    /**
     * A buffer, waiting until one is recycled.
     *
     * @param interrupted checked while waiting: a cancelled task stops waiting
     * @throws PipeForgeException when the pool fails while waiting
     */
    public Buffer take(Supplier<Boolean> interrupted) throws InterruptedException {
        synchronized (this) {
            try {
                while (true) {
                    Buffer buffer = poll();
                    if (buffer != null) {
                        return buffer;
                    }
                    if (interrupted.get() || Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("waiting for a network buffer of " + owner);
                    }
                    if (waitingSince == 0) {
                        waitingSince = System.nanoTime();
                    }
                    wait(Duration.ofMillis(50).toMillis());
                }
            } finally {
                if (waitingSince != 0) {
                    waitNanos += System.nanoTime() - waitingSince;
                    waitingSince = 0;
                }
            }
        }
    }

    private synchronized void recycle(byte[] memory) {
        inUse--;
        network.giveBack(memory);
        if (destroyed) {
            releaseUnused();
        } else if (reserved > min && inUse < reserved) {
            // buffers above the reservation go back to the network pool as soon as they are free
            reserved--;
            network.unreserve(1);
        }
        notifyAll();
    }

    /** Fails waiting and later requests with {@code error}. */
    public synchronized void fail(PipeForgeException error) {
        if (failure == null) {
            failure = error;
        }
        notifyAll();
    }

    /** Gives all buffers back to the network pool once they are recycled. */
    public synchronized void destroy() {
        destroyed = true;
        releaseUnused();
        notifyAll();
    }

    private void releaseUnused() {
        int unused = reserved - inUse;
        if (unused > 0) {
            reserved -= unused;
            network.unreserve(unused);
        }
    }

    private void check() {
        if (failure != null) {
            throw failure;
        }
        if (destroyed) {
            throw new IllegalStateException("the buffer pool of " + owner + " is destroyed");
        }
    }

    public synchronized int buffersInUse() {
        return inUse;
    }

    public int maxBuffers() {
        return max;
    }

    /** The share of the buffers in use, from 0 to 1. */
    public synchronized double usage() {
        return (double) inUse / max;
    }

    /** Nanoseconds spent waiting for a buffer, including a wait that has not ended. */
    public synchronized long waitNanos() {
        return waitNanos + (waitingSince == 0 ? 0 : System.nanoTime() - waitingSince);
    }
}
