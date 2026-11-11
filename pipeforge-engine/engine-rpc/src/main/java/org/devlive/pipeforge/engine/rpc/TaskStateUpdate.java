/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.List;
import java.util.Objects;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * A task reports a change of its state.
 *
 * @param id the task attempt
 * @param state the new state
 * @param error the error when {@code state} is {@link TaskState#FAILED}, otherwise {@code null}
 * @param committables what the sink prepared to commit when the task finished; empty otherwise
 * @param metrics the final metrics of a task with a final state; {@code null} otherwise
 */
public record TaskStateUpdate(
        ExecutionAttemptId id,
        TaskState state,
        @Nullable PipeForgeException error,
        List<SerializedValue> committables,
        @Nullable TaskMetricsSnapshot metrics) {

    public TaskStateUpdate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        committables = List.copyOf(committables);
        if ((state == TaskState.FAILED) != (error != null)) {
            throw new IllegalArgumentException("A task state update has an error exactly when the task failed");
        }
    }

    public static TaskStateUpdate of(ExecutionAttemptId id, TaskState state) {
        return new TaskStateUpdate(id, state, null, List.of(), null);
    }

    public static TaskStateUpdate failed(ExecutionAttemptId id, PipeForgeException error) {
        return new TaskStateUpdate(id, TaskState.FAILED, error, List.of(), null);
    }

    public static TaskStateUpdate finished(ExecutionAttemptId id, List<SerializedValue> committables) {
        return new TaskStateUpdate(id, TaskState.FINISHED, null, committables, null);
    }

    /** This update with the final metrics of the task. */
    public TaskStateUpdate withMetrics(TaskMetricsSnapshot value) {
        return new TaskStateUpdate(id, state, error, committables, value);
    }
}
