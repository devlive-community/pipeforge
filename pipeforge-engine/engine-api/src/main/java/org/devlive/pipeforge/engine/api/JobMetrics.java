/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Job-level metrics, summed over all tasks, with the metrics of every node. Every metric is listed
 * with its type, unit and meaning in the metric catalog ({@link MetricCatalog}).
 *
 * <p>The counters reconcile: {@code rowsRead + rowsExpanded = rowsWritten + rowsFiltered +
 * rowsFailed + rowsInFlight}. While the job runs, the right side includes the rows on their way to
 * the target; when it ends, {@code rowsInFlight} is 0 and rows that never reached the target are
 * {@code rowsFailed}.
 *
 * @param elapsedMs time since the job was submitted, until it ended
 * @param rowsRead rows emitted by the sources
 * @param rowsWritten rows written by the sinks; for sinks that commit in two phases, the rows
 *     committed
 * @param rowsFiltered rows that transforms dropped
 * @param rowsExpanded extra rows that transforms emitted when one row became several
 * @param rowsFailed rows read but not written when the job failed or was cancelled
 * @param rowsInFlight rows read but not yet written, e.g. in the write queue
 * @param readRowsPerSecond the read rate
 * @param writeRowsPerSecond the write rate
 * @param readerWaitMs time tasks blocked on a full write queue, summed over tasks: the target is slow
 * @param writerWaitMs time write threads waited for rows, summed over tasks: the source is slow
 * @param parallelism the number of tasks
 * @param tasksRunning the tasks that are running now
 * @param nodes the metrics of every node, in the order of the plan
 * @param exchangedBytes bytes of rows tasks sent to other tasks
 * @param backpressuredMs time tasks waited for network buffers, summed over tasks: something
 *     downstream is slower
 * @param backpressuredRatio {@code backpressuredMs} as a share of the running time of the tasks,
 *     from 0 to 1; -1 before any task ran
 * @param queueDepth batches in the write queues of the sinks now
 * @param queueCapacity the batches the write queues of the sinks hold
 * @param splitsAssigned splits the enumerator gave to readers
 * @param splitsFinished splits of readers that have finished
 * @param runningMs time tasks ran, summed over tasks: the base of the wait shares
 */
public record JobMetrics(
        long elapsedMs,
        long rowsRead,
        long rowsWritten,
        long rowsFiltered,
        long rowsExpanded,
        long rowsFailed,
        long rowsInFlight,
        Rate readRowsPerSecond,
        Rate writeRowsPerSecond,
        long readerWaitMs,
        long writerWaitMs,
        int parallelism,
        int tasksRunning,
        List<NodeMetrics> nodes,
        long exchangedBytes,
        long backpressuredMs,
        double backpressuredRatio,
        int queueDepth,
        int queueCapacity,
        int splitsAssigned,
        int splitsFinished,
        long runningMs) {

    /** No data yet. */
    public static final JobMetrics EMPTY =
            new JobMetrics(0, 0, 0, 0, 0, 0, 0, Rate.ZERO, Rate.ZERO, 0, 0, 0, 0, List.of(), 0, 0, -1, 0, 0, 0, 0, 0);

    public JobMetrics {
        Objects.requireNonNull(readRowsPerSecond, "readRowsPerSecond");
        Objects.requireNonNull(writeRowsPerSecond, "writeRowsPerSecond");
        nodes = List.copyOf(nodes);
    }

    /** Whether the row counters satisfy the reconciliation identity. */
    public boolean reconciled() {
        return rowsRead + rowsExpanded == rowsWritten + rowsFiltered + rowsFailed + rowsInFlight;
    }

    public Optional<NodeMetrics> node(String id) {
        return nodes.stream().filter(n -> n.node().equals(id)).findFirst();
    }
}
