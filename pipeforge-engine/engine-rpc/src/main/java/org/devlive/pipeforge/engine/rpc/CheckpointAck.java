/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.List;

/**
 * A task's part of checkpoint {@code checkpointId}: a state for each stateful node of its chain,
 * and what taking them cost.
 */
public record CheckpointAck(
        ExecutionAttemptId task, long checkpointId, List<StateHandle> states, CheckpointTaskMetrics metrics) {

    public CheckpointAck {
        states = List.copyOf(states);
    }
}
