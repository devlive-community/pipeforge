/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.List;

/**
 * Cumulative metrics of one task since it started running. Snapshots replace each other, so a lost
 * or late report never makes a counter wrong, only stale.
 *
 * @param operators the operators of the chain, from the source to the sink
 * @param runningNanos time since the task started running, until it ended
 * @param idleNanos time the task thread waited because the source had nothing to read
 * @param readerWaitNanos time the task thread blocked on a full write queue (the target is slow)
 * @param writerWaitNanos time the write thread waited on an empty queue (the source is slow)
 * @param backpressuredNanos time the task waited for network buffers: the tasks it sends rows to
 *     were slower
 * @param bytesSent bytes of rows the task sent to other tasks
 * @param bytesReceived bytes of rows the task received from other tasks
 * @param queueDepth batches in the write queue of the sink now
 * @param queueCapacity the batches the write queue holds; 0 without one
 */
public record TaskMetricsSnapshot(
        List<OperatorSnapshot> operators,
        long runningNanos,
        long idleNanos,
        long readerWaitNanos,
        long writerWaitNanos,
        long backpressuredNanos,
        long bytesSent,
        long bytesReceived,
        int queueDepth,
        int queueCapacity) {

    public static final TaskMetricsSnapshot EMPTY = new TaskMetricsSnapshot(List.of(), 0, 0, 0, 0);

    public TaskMetricsSnapshot {
        operators = List.copyOf(operators);
    }

    /** A snapshot of a task that exchanges no rows with other tasks and has no write queue. */
    public TaskMetricsSnapshot(
            List<OperatorSnapshot> operators,
            long runningNanos,
            long idleNanos,
            long readerWaitNanos,
            long writerWaitNanos) {
        this(operators, runningNanos, idleNanos, readerWaitNanos, writerWaitNanos, 0, 0, 0, 0, 0);
    }
}
