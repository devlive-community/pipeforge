/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.Objects;

/**
 * Identifies one attempt of one parallel instance of a vertex: {@code
 * <runId>/<vertexId>/<subtask>/<attempt>}. A restarted task gets a new attempt number, so that
 * messages for the old attempt can be told apart and dropped.
 *
 * @param runId the run the task belongs to
 * @param vertexId the vertex (operator chain) of the task
 * @param subtask the parallel instance, from 0
 * @param attempt the attempt, from 0
 */
public record ExecutionAttemptId(String runId, String vertexId, int subtask, int attempt) {

    public ExecutionAttemptId {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(vertexId, "vertexId");
        if (subtask < 0 || attempt < 0) {
            throw new IllegalArgumentException("subtask and attempt must not be negative");
        }
    }

    @Override
    public String toString() {
        return runId + "/" + vertexId + "/" + subtask + "/" + attempt;
    }
}
