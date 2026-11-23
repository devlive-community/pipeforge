/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.checkpoint;

import java.time.Instant;
import java.util.List;
import org.devlive.pipeforge.engine.rpc.CheckpointType;
import org.jspecify.annotations.Nullable;

/**
 * Where the master records the checkpoints of runs: the {@code checkpoint} table of the metastore
 * (protocol specification, section 2.6). A checkpoint is recorded as {@link Status#COMPLETED}
 * before any task hears that it is complete, so that the latest completed checkpoint is never
 * older than data a sink committed.
 */
public interface CheckpointStore {

    enum Status {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    /**
     * A checkpoint of a run.
     *
     * @param reason why it was taken: {@code PERIODIC}, {@code SAVEPOINT}, ...
     * @param name the name of a savepoint
     * @param directory its directory in the checkpoint storage
     * @param tasksJson each task's part, as JSON
     */
    record Checkpoint(
            String runId,
            long checkpointId,
            String jobName,
            int attempt,
            CheckpointType type,
            Status status,
            String reason,
            @Nullable String name,
            Instant triggeredAt,
            @Nullable Instant completedAt,
            @Nullable Long size,
            String directory,
            @Nullable String errorCode,
            @Nullable String errorMessage,
            @Nullable String tasksJson) {}

    /** The highest checkpoint number of a run, 0 without any: numbers never repeat within a run. */
    long lastCheckpointId(String runId);

    /** Records a checkpoint that was just triggered. */
    void begin(Checkpoint checkpoint);

    /** Records that a checkpoint is complete, and that it is the last of its attempt. */
    void complete(String runId, long checkpointId, int attempt, Instant completedAt, long size, String tasksJson);

    /** Records that a checkpoint failed. */
    void fail(String runId, long checkpointId, Instant at, String errorCode, String errorMessage);

    /** Forgets a checkpoint whose files were deleted. */
    void delete(String runId, long checkpointId);

    /** The checkpoints of a run, the newest first. */
    List<Checkpoint> list(String runId);
}
