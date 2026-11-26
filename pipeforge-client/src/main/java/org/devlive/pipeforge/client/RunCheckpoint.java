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
 * A checkpoint or savepoint of a run.
 *
 * @param id its number, counting up across the attempts of the run
 * @param attempt the attempt that took it
 * @param type CHECKPOINT or SAVEPOINT
 * @param status IN_PROGRESS, COMPLETED or FAILED
 * @param reason why it was taken, e.g. PERIODIC, SAVEPOINT or PAUSE
 * @param name the name of a savepoint
 * @param triggeredAt when it was taken
 * @param completedAt when it completed
 * @param sizeBytes the size of its states
 * @param path its directory in the checkpoint storage
 * @param error why it failed
 * @param tasks each task's part of a completed one
 */
public record RunCheckpoint(
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
        List<CheckpointTask> tasks) {

    public RunCheckpoint {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
