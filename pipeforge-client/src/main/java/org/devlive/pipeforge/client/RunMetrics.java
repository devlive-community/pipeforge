/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The metrics of a run now, or at its end.
 *
 * @param at when they were taken
 * @param elapsedMs how long the run has been running
 * @param parallelism the tasks of the run
 * @param tasksRunning the tasks running now
 * @param rowsRead rows the sources read
 * @param rowsWritten rows the sinks wrote
 * @param rowsFiltered rows transforms dropped
 * @param rowsExpanded rows transforms added
 * @param rowsFailed rows that failed
 * @param rowsDirty bad rows skipped
 * @param rowsInFlight rows between the readers and the writers
 * @param readRowsPerSecond the read rate now
 * @param writeRowsPerSecond the write rate now
 * @param readerWaitMs time readers waited for the writers
 * @param writerWaitMs time writers waited for the readers
 * @param throttleWaitMs time readers waited for the rate limit
 * @param backpressuredMs time tasks waited for network buffers
 * @param exchangedBytes bytes sent between tasks
 * @param memoryBytes memory the tasks account now
 * @param queueDepth batches in the write queues now
 * @param queueCapacity batches the write queues hold
 * @param splitsTotal splits in all, or assigned so far when the source does not say
 * @param splitsFinished splits read
 * @param nodes the counters of each node of the plan
 * @param dirtySamples the first bad rows skipped
 * @param lastCompletedCheckpointId the last completed checkpoint, 0 before the first
 * @param lastCheckpointDurationMs how long it took
 * @param lastCheckpointSize the size of its state
 * @param checkpointFailures checkpoints that failed in a row
 */
public record RunMetrics(
        @Nullable Instant at,
        long elapsedMs,
        int parallelism,
        int tasksRunning,
        long rowsRead,
        long rowsWritten,
        long rowsFiltered,
        long rowsExpanded,
        long rowsFailed,
        long rowsDirty,
        long rowsInFlight,
        double readRowsPerSecond,
        double writeRowsPerSecond,
        long readerWaitMs,
        long writerWaitMs,
        long throttleWaitMs,
        long backpressuredMs,
        long exchangedBytes,
        long memoryBytes,
        int queueDepth,
        int queueCapacity,
        int splitsTotal,
        int splitsFinished,
        List<NodeMetrics> nodes,
        List<DirtySample> dirtySamples,
        long lastCompletedCheckpointId,
        long lastCheckpointDurationMs,
        long lastCheckpointSize,
        long checkpointFailures) {

    public RunMetrics {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        dirtySamples = dirtySamples == null ? List.of() : List.copyOf(dirtySamples);
    }
}
