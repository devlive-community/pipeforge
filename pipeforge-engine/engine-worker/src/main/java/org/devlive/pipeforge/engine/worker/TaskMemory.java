/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * The memory a task holds, by what holds it: rows in the write buffer, network buffers of its
 * inputs and outputs, and split assignments that wait in its mailbox. A task that ended accounts
 * nothing; anything left is a leak, which the task executor reports.
 */
final class TaskMemory {

    private final MemoryLimits limits;
    private final AtomicLong splits = new AtomicLong();
    private volatile LongSupplier writeBuffer = () -> 0;
    private volatile LongSupplier network = () -> 0;

    TaskMemory(MemoryLimits limits) {
        this.limits = limits;
    }

    MemoryLimits limits() {
        return limits;
    }

    /**
     * The estimated size of a row that entered the task at {@code node}.
     *
     * @throws PipeForgeException {@code PF-DATA-0007} when it is larger than {@code
     *     task.max-row-bytes}
     */
    long check(Row row, String node) {
        long size = RowSize.estimate(row);
        if (size > limits.maxRowBytes()) {
            throw PipeForgeException.of(ErrorCode.ROW_TOO_LARGE)
                    .param("table", String.valueOf(row.table()))
                    .param("bytes", size)
                    .param("limit", limits.maxRowBytes())
                    .scope(ErrorScope.node(node))
                    .build();
        }
        return size;
    }

    void writeBuffer(LongSupplier bytes) {
        this.writeBuffer = bytes;
    }

    void network(LongSupplier bytes) {
        this.network = bytes;
    }

    void splitsQueued(long bytes) {
        splits.addAndGet(bytes);
    }

    void splitsTaken(long bytes) {
        splits.addAndGet(-bytes);
    }

    /** What the task holds now, in bytes. */
    long accounted() {
        return writeBuffer.getAsLong() + network.getAsLong() + splits.get();
    }

    /** What the task holds now, by holder. */
    Map<String, Long> byHolder() {
        Map<String, Long> holders = new LinkedHashMap<>();
        holders.put("writeBuffer", writeBuffer.getAsLong());
        holders.put("network", network.getAsLong());
        holders.put("splits", splits.get());
        return holders;
    }

    /**
     * Called when the operators closed. Stops counting the network buffers: rows the task wrote stay
     * in the data exchange until the tasks that read them took them, but they no longer belong to
     * this task. Drops the splits still in the mailbox, which no one will run. The write buffer is
     * not released here: it is empty once the operators closed, or it leaks.
     */
    void release() {
        network = () -> 0;
        splits.set(0);
    }
}
