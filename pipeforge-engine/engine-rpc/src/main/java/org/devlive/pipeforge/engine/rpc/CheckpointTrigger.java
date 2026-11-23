/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

/**
 * Starts checkpoint {@code checkpointId} at a source task; the other tasks start it when its
 * barrier reaches them (protocol specification, section 2.6).
 *
 * @param stop for stop-with-savepoint: the source reads nothing after the snapshot and ends its
 *     input
 */
public record CheckpointTrigger(
        ExecutionAttemptId task, long checkpointId, CheckpointType type, long timestampMs, boolean stop) {

    public CheckpointTrigger {
        if (checkpointId <= 0) {
            throw new IllegalArgumentException("Checkpoint IDs start at 1, not " + checkpointId);
        }
    }
}
