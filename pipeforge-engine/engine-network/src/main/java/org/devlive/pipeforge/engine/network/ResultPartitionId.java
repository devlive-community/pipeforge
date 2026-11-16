/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network;

/**
 * An output of a task attempt: the {@code ExecutionAttemptId} of the producer and the number of its
 * output edge.
 */
public record ResultPartitionId(String runId, String vertexId, int subtask, int attempt, int outputIndex) {

    public ResultPartitionId {
        if (subtask < 0 || attempt < 0 || outputIndex < 0) {
            throw new IllegalArgumentException("negative number in a partition id");
        }
    }

    @Override
    public String toString() {
        return runId + "/" + vertexId + "/" + subtask + "/" + attempt + "#" + outputIndex;
    }
}
