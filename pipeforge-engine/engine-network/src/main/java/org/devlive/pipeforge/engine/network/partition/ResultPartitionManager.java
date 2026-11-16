/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.partition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.NetworkOptions;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.LocalBufferPool;
import org.devlive.pipeforge.engine.network.buffer.NetworkBufferPool;
import org.devlive.pipeforge.engine.network.row.RowCodec;

/**
 * The outputs of the tasks of a worker. A consumer may ask for an output before its task has
 * created it; the request waits until the output appears or the partition request timeout passes.
 */
public final class ResultPartitionManager {

    private final NetworkBufferPool pool;
    private final NetworkOptions options;
    private final String workerId;
    private final Map<ResultPartitionId, ResultPartition> partitions = new HashMap<>();
    private final Map<ResultPartitionId, List<CompletableFuture<ResultPartition>>> waiting = new HashMap<>();

    public ResultPartitionManager(NetworkBufferPool pool, NetworkOptions options, String workerId) {
        this.pool = pool;
        this.options = options;
        this.workerId = workerId;
    }

    /**
     * Creates and registers the output of a task. Its pool reserves one buffer per subpartition
     * and one more, and may grow to {@code buffers-per-channel} per subpartition plus the floating
     * buffers of an input.
     */
    public ResultPartition create(ResultPartitionId id, int subpartitions, RowCodec codec) {
        int min = subpartitions + 1;
        int max = Math.max(min, subpartitions * options.buffersPerChannel() + options.floatingBuffersPerGate());
        LocalBufferPool local = pool.createLocalPool("the output " + id, min, max);
        ResultPartition partition = new ResultPartition(
                id, subpartitions, local, codec, options.bufferSize(), options.maxRowBytes(), () -> remove(id));
        List<CompletableFuture<ResultPartition>> requests;
        synchronized (this) {
            if (partitions.putIfAbsent(id, partition) != null) {
                local.destroy();
                throw new IllegalStateException("output " + id + " exists already");
            }
            requests = waiting.remove(id);
        }
        if (requests != null) {
            requests.forEach(r -> r.complete(partition));
        }
        return partition;
    }

    /**
     * The output {@code id}, now or once it is created.
     *
     * @return a future that fails with {@link ErrorCode#PARTITION_NOT_FOUND} after the timeout
     */
    public CompletableFuture<ResultPartition> partition(ResultPartitionId id, ScheduledExecutorService timer) {
        CompletableFuture<ResultPartition> future = new CompletableFuture<>();
        synchronized (this) {
            ResultPartition partition = partitions.get(id);
            if (partition != null) {
                future.complete(partition);
                return future;
            }
            waiting.computeIfAbsent(id, k -> new ArrayList<>()).add(future);
        }
        Duration timeout = options.partitionRequestTimeout();
        var unused = timer.schedule(
                () -> {
                    synchronized (this) {
                        List<CompletableFuture<ResultPartition>> requests = waiting.get(id);
                        if (requests != null) {
                            requests.remove(future);
                            if (requests.isEmpty()) {
                                waiting.remove(id);
                            }
                        }
                    }
                    future.completeExceptionally(notFound(id, workerId, timeout));
                },
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        return future;
    }

    static PipeForgeException notFound(ResultPartitionId id, String worker, Duration timeout) {
        return PipeForgeException.of(ErrorCode.PARTITION_NOT_FOUND)
                .param("partition", id.toString())
                .param("worker", worker)
                .param("seconds", Math.max(1, timeout.toSeconds()))
                .build();
    }

    /** The output, if it is registered. */
    public synchronized @org.jspecify.annotations.Nullable ResultPartition get(ResultPartitionId id) {
        return partitions.get(id);
    }

    public synchronized int size() {
        return partitions.size();
    }

    /** Flushes the buffers of all outputs that are not full; see the buffer timeout. */
    public void flushAll() {
        List<ResultPartition> all;
        synchronized (this) {
            all = List.copyOf(partitions.values());
        }
        all.forEach(ResultPartition::flush);
    }

    /** Releases every output; when the worker stops. */
    public void releaseAll() {
        List<ResultPartition> all;
        synchronized (this) {
            all = List.copyOf(partitions.values());
        }
        all.forEach(ResultPartition::release);
    }

    private synchronized void remove(ResultPartitionId id) {
        partitions.remove(id);
    }
}
