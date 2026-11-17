/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.partition;

import java.util.function.Supplier;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.NetworkEvent;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.devlive.pipeforge.engine.network.buffer.LocalBufferPool;
import org.devlive.pipeforge.engine.network.row.RowBuffer;
import org.devlive.pipeforge.engine.network.row.RowCodec;
import org.jspecify.annotations.Nullable;

/**
 * An output of a task: one subpartition per consumer subtask. The task writes rows into buffers of
 * its pool; a full buffer, one older than the buffer timeout or one before an event goes to the
 * consumer. When the consumers are slow the pool runs out and writing waits — the task is
 * backpressured. Written by one task thread.
 */
public final class ResultPartition {

    /** What the output measured. */
    public record Metrics(long rows, long bytes, long buffers, long backpressuredMs, double bufferUsage) {}

    private final ResultPartitionId id;
    private final ResultSubpartition[] subpartitions;
    private final LocalBufferPool pool;
    private final RowCodec codec;
    private final int bufferSize;
    private final int maxRowBytes;
    private final Runnable onDone;
    private final RowBuffer scratch = new RowBuffer(1024);
    private volatile @Nullable PipeForgeException failure;
    private volatile boolean finished;
    private int done;
    private long rows;
    private long bytes;
    private long buffers;

    ResultPartition(
            ResultPartitionId id,
            int subpartitions,
            LocalBufferPool pool,
            RowCodec codec,
            int bufferSize,
            int maxRowBytes,
            Runnable onDone) {
        this.id = id;
        this.subpartitions = new ResultSubpartition[subpartitions];
        for (int i = 0; i < subpartitions; i++) {
            this.subpartitions[i] = new ResultSubpartition(this, i);
        }
        this.pool = pool;
        this.codec = codec;
        this.bufferSize = bufferSize;
        this.maxRowBytes = maxRowBytes;
        this.onDone = onDone;
    }

    public ResultPartitionId id() {
        return id;
    }

    public int subpartitions() {
        return subpartitions.length;
    }

    /**
     * Writes a row to a subpartition, waiting for a buffer when all are in use.
     *
     * @param interrupted checked while waiting, so that a cancelled task stops
     * @throws PipeForgeException {@link ErrorCode#ROW_TOO_LARGE} for a row above
     *     {@code task.max-row-bytes}, or the error the output failed with
     */
    public void emit(int subpartition, Row row, @Nullable Long eventTime, Supplier<Boolean> interrupted)
            throws InterruptedException {
        check();
        scratch.clear();
        codec.encode(row, eventTime, scratch);
        int size = scratch.size();
        if (size > maxRowBytes) {
            throw PipeForgeException.of(ErrorCode.ROW_TOO_LARGE)
                    .param("table", row.table().toString())
                    .param("bytes", size)
                    .param("limit", maxRowBytes)
                    .build();
        }
        ResultSubpartition target = subpartitions[subpartition];
        rows++;
        bytes += size;
        if (target.append(scratch)) {
            return;
        }
        if (size > bufferSize) {
            // a row larger than a buffer has a buffer of its own, outside the pool
            buffers++;
            target.add(Buffer.unpooled(Buffer.Kind.DATA, scratch.toByteArray()));
            return;
        }
        if (target.isDone()) {
            // the consumer of this subpartition is gone: its rows are dropped
            return;
        }
        Buffer buffer;
        try {
            buffer = pool.take(() -> interrupted.get() || failure != null);
        } catch (IllegalStateException e) {
            if (target.isDone()) {
                return;
            }
            throw e;
        }
        check();
        buffers++;
        if (target.start(buffer) && !target.append(scratch)) {
            if (target.isDone()) {
                // the consumer released the subpartition between start and append: the row is dropped
                return;
            }
            throw new IllegalStateException("a row of " + size + " bytes does not fit an empty buffer");
        }
    }

    /** Puts an event into one subpartition, after the rows written before it. */
    public void emitEvent(int subpartition, NetworkEvent event) {
        check();
        subpartitions[subpartition].add(NetworkEvent.toBuffer(event));
    }

    /** Puts an event into every subpartition. */
    public void broadcastEvent(NetworkEvent event) {
        for (int i = 0; i < subpartitions.length; i++) {
            emitEvent(i, event);
        }
    }

    /** Sends the buffers that are not full; called every buffer timeout. */
    public void flush() {
        for (ResultSubpartition subpartition : subpartitions) {
            subpartition.flush();
        }
    }

    /** Ends every subpartition; the output is released once its consumers have read it all. */
    public void finish() {
        if (!finished) {
            broadcastEvent(NetworkEvent.END_OF_PARTITION_EVENT);
            finished = true;
        }
    }

    public boolean isFinished() {
        return finished;
    }

    /** Fails the writer (it may be waiting for a buffer) and drops all buffers. */
    public void fail(PipeForgeException error) {
        if (failure == null) {
            failure = error;
        }
        pool.fail(error);
        release();
    }

    public @Nullable PipeForgeException failure() {
        return failure;
    }

    /** Drops all buffers and releases the output. */
    public void release() {
        for (ResultSubpartition subpartition : subpartitions) {
            subpartition.release();
        }
    }

    /**
     * The view of the one consumer of a subpartition.
     *
     * @param available called whenever a buffer becomes available, from any thread
     */
    public SubpartitionView createView(int subpartition, Runnable available) {
        if (subpartition < 0 || subpartition >= subpartitions.length) {
            throw new IllegalArgumentException(
                    "subpartition " + subpartition + " of " + id + " with " + subpartitions.length);
        }
        return subpartitions[subpartition].view(available);
    }

    void subpartitionDone() {
        boolean all;
        synchronized (this) {
            done++;
            all = done == subpartitions.length;
        }
        if (all) {
            pool.destroy();
            onDone.run();
        }
    }

    public synchronized boolean isReleased() {
        return done == subpartitions.length;
    }

    /** Bytes of the network buffers the output holds now: its share of the worker's network memory. */
    public long bytesInUse() {
        return (long) pool.buffersInUse() * bufferSize;
    }

    public Metrics metrics() {
        return new Metrics(rows, bytes, buffers, pool.waitNanos() / 1_000_000, pool.usage());
    }

    private void check() {
        PipeForgeException error = failure;
        if (error != null) {
            throw error;
        }
        if (finished) {
            throw new IllegalStateException("output " + id + " is finished");
        }
    }
}
