/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.time.Instant;
import java.util.Objects;

/**
 * Asks a task executor to run one task.
 *
 * @param id the task attempt
 * @param chain the operators of the task
 * @param parallelism the number of parallel instances of the vertex
 * @param settings runtime settings
 * @param runStartTime when the run started; the same for every task of the run and across retries,
 *     it is what {@code RUN_START_TIME()} returns in expressions
 */
public record TaskDeployment(
        ExecutionAttemptId id, ChainDescriptor chain, int parallelism, TaskSettings settings, Instant runStartTime) {

    public TaskDeployment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(runStartTime, "runStartTime");
        if (id.subtask() >= parallelism) {
            throw new IllegalArgumentException("Subtask " + id.subtask() + " is outside parallelism " + parallelism);
        }
    }
}
