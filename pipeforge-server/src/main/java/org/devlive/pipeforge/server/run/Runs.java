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

/** The records of runs the server keeps, as the API shows them. */
public final class Runs {

    private Runs() {}

    /**
     * A run.
     *
     * @param id the run ID
     * @param job the job's name
     * @param status where the run is
     * @param submittedBy who submitted it; empty until the server has users
     * @param error why the run failed, for runs that ended without success
     * @param rowsRead rows the sources read, as of the last sample of the run's metrics; {@code
     *     null} before the first sample
     * @param rowsWritten rows the sinks wrote, as of the same sample
     * @param attempt the run's current attempt, counted from 1
     * @param retryAt when a run in {@code RETRY_WAIT} starts its next attempt; {@code null} otherwise
     */
    public record Run(
            String id,
            String job,
            RunState status,
            @Nullable String submittedBy,
            Instant submittedAt,
            @Nullable Instant startedAt,
            @Nullable Instant endedAt,
            Instant updatedAt,
            @Nullable RunError error,
            @Nullable Long rowsRead,
            @Nullable Long rowsWritten,
            int attempt,
            @Nullable Instant retryAt) {}

    /** An error of a run: its code and its message in English; the code's page tells more. */
    public record RunError(String code, String message) {}

    /**
     * A change of a run's state.
     *
     * @param from the state before; {@code null} when the run was submitted
     * @param reason why it changed, e.g. the error code of a failure
     */
    public record Transition(int attempt, @Nullable RunState from, RunState to, Instant at, @Nullable String reason) {}

    /** A run with its job definition, every change of its state and every attempt, oldest first. */
    public record RunDetail(Run run, String definition, List<Transition> transitions, List<Attempt> attempts) {}

    /**
     * One attempt of a run.
     *
     * @param restorePath the checkpoint it started from; {@code null} when it started from the beginning
     */
    public record Attempt(
            int attempt,
            RunState status,
            Instant startedAt,
            @Nullable Instant endedAt,
            @Nullable RunError error,
            @Nullable String restorePath) {}

    /**
     * A checkpoint or savepoint of a run, as the master recorded it.
     *
     * @param type {@code CHECKPOINT} or {@code SAVEPOINT}
     * @param status {@code IN_PROGRESS}, {@code COMPLETED} or {@code FAILED}
     * @param reason why it was taken: {@code PERIODIC}, {@code SAVEPOINT}, {@code PAUSE}, {@code FINISH}, ...
     * @param name the name of a savepoint
     * @param sizeBytes the size of its states, once completed
     * @param path its directory in the checkpoint storage
     * @param error why it failed
     * @param tasks each task's part of a completed one
     */
    public record Checkpoint(
            long id,
            int attempt,
            String type,
            String status,
            String reason,
            @Nullable String name,
            Instant triggeredAt,
            @Nullable Instant completedAt,
            @Nullable Long sizeBytes,
            String path,
            @Nullable RunError error,
            List<CheckpointTask> tasks) {}

    /**
     * A task's part of a checkpoint.
     *
     * @param task the execution attempt of the task, {@code run/vertex/subtask/attempt}
     * @param states how many states it wrote
     * @param syncMs how long its snapshot held the task up
     * @param asyncMs how long the upload took
     * @param alignmentMs how long it waited for the barriers of its inputs
     * @param stateBytes the size of its states
     */
    public record CheckpointTask(
            String task, int states, long syncMs, long asyncMs, long alignmentMs, long stateBytes) {}

    /**
     * An attempt of a run with what it did.
     *
     * @param retryAt when the next attempt starts, while the run waits in {@code RETRY_WAIT}
     * @param rowsRead the rows the attempt read, from its last metrics sample; {@code null} without
     * @param elapsedMs how long the attempt has been running, from its last metrics sample
     */
    public record AttemptView(
            int attempt,
            RunState status,
            Instant startedAt,
            @Nullable Instant endedAt,
            @Nullable RunError error,
            @Nullable String restorePath,
            @Nullable Instant retryAt,
            @Nullable Long rowsRead,
            @Nullable Long rowsWritten,
            @Nullable Long rowsDirty,
            @Nullable Long elapsedMs) {}

    /**
     * A page of runs, newest first.
     *
     * @param next the token of the next page; {@code null} on the last page
     */
    public record RunPage(List<Run> runs, @Nullable String next) {}
}
