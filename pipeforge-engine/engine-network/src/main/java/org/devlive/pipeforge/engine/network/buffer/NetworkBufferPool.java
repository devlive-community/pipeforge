/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.buffer;

import java.util.ArrayDeque;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * The network memory of a worker as buffers of one size ({@code worker.memory.network}). The
 * memory of a buffer is created when it is first taken and reused afterwards. Outputs and inputs
 * take their buffers through a {@link LocalBufferPool} with a share of it.
 */
public final class NetworkBufferPool {

    private final int bufferSize;
    private final int total;
    private final ArrayDeque<byte[]> free = new ArrayDeque<>();
    private int created;
    private int reserved;

    public NetworkBufferPool(int total, int bufferSize) {
        if (total < 1) {
            throw new IllegalArgumentException("a pool needs at least one buffer");
        }
        this.total = total;
        this.bufferSize = bufferSize;
    }

    public int bufferSize() {
        return bufferSize;
    }

    public int totalBuffers() {
        return total;
    }

    /** Buffers not reserved by any local pool. */
    public synchronized int availableBuffers() {
        return total - reserved;
    }

    /**
     * A local pool that may hold up to {@code max} buffers, {@code min} of them reserved now.
     *
     * @param owner how errors name the user, e.g. the input of a task
     * @throws PipeForgeException {@link ErrorCode#NETWORK_BUFFERS_INSUFFICIENT} when fewer than
     *     {@code min} buffers are free
     */
    public LocalBufferPool createLocalPool(String owner, int min, int max) {
        reserve(owner, min);
        return new LocalBufferPool(this, owner, min, max);
    }

    synchronized void reserve(String owner, int count) {
        if (count > total - reserved) {
            throw PipeForgeException.of(ErrorCode.NETWORK_BUFFERS_INSUFFICIENT)
                    .param("owner", owner)
                    .param("required", count)
                    .param("available", total - reserved)
                    .param("total", total)
                    .build();
        }
        reserved += count;
    }

    /** One more buffer for a local pool beyond its reservation, if one is free. */
    synchronized boolean tryReserve() {
        if (reserved >= total) {
            return false;
        }
        reserved++;
        return true;
    }

    synchronized void unreserve(int count) {
        reserved -= count;
    }

    synchronized byte[] memory() {
        byte[] memory = free.poll();
        if (memory != null) {
            return memory;
        }
        created++;
        return new byte[bufferSize];
    }

    synchronized void giveBack(byte[] memory) {
        free.push(memory);
    }

    /** Buffers whose memory exists. */
    public synchronized int createdBuffers() {
        return created;
    }
}
