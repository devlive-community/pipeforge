/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The metrics of a run at one moment, as the master aggregates them (metric catalog, design 15).
 *
 * @param at when they were taken
 * @param readRowsPerSecond the read rate now
 * @param readerWaitMs time readers waited for a full write queue: the target is slow
 * @param writerWaitMs time writers waited for rows: the source is slow
 * @param backpressuredMs time tasks waited for network buffers
 * @param throttleWaitMs time tasks waited for the job's rate limit
 * @param splitsTotal splits the enumerator assigned
 */
public record RunMetrics(
        Instant at,
        long elapsedMs,
        long rowsRead,
        long rowsWritten,
        long rowsFiltered,
        long rowsExpanded,
        long rowsDirty,
        long rowsFailed,
        long rowsInFlight,
        double readRowsPerSecond,
        double writeRowsPerSecond,
        long readerWaitMs,
        long writerWaitMs,
        long backpressuredMs,
        long throttleWaitMs,
        long exchangedBytes,
        long memoryBytes,
        int queueDepth,
        int queueCapacity,
        int tasksRunning,
        int parallelism,
        int splitsTotal,
        int splitsFinished,
        List<Node> nodes,
        List<DirtySample> dirtySamples) {

    public RunMetrics {
        nodes = List.copyOf(nodes);
        dirtySamples = List.copyOf(dirtySamples);
    }

    /** The counters of one node of the plan. */
    public record Node(
            String node,
            long rowsIn,
            long rowsOut,
            long rowsFiltered,
            long rowsExpanded,
            long rowsDirty,
            long bytesIn,
            long bytesOut) {}

    /** A bad row the run skipped: where, why and the row as text. */
    public record DirtySample(
            String node, @Nullable String table, String code, String message, String row, Instant at) {}

    /** A sample of the main counters, for charts of a run over time; rates follow from two samples. */
    public record Sample(
            Instant at, long elapsedMs, long rowsRead, long rowsWritten, long rowsDirty, long rowsFailed) {}

    /** No metrics: a run that ended before any were taken. */
    public static RunMetrics none(Instant at) {
        return new RunMetrics(
                at, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    /** A task of a run, where it runs and how far it got. */
    public record Task(String vertex, int subtask, int attempt, String state, String worker) {}

    /**
     * A split and its reader.
     *
     * @param state {@code ASSIGNED} while its reader reads, {@code FINISHED} once the reader finished
     */
    public record Split(String node, String split, int subtask, String state) {}

    /**
     * A part of a task's log.
     *
     * @param offset where the next read continues
     * @param eof whether the log was read to its end and the task has ended
     */
    public record LogChunk(long offset, String text, boolean eof) {}
}
