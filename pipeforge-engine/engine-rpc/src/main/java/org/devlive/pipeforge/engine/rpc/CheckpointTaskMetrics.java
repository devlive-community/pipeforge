/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

/**
 * What one task's part of a checkpoint cost.
 *
 * @param syncMs taking the copy of the state, while the task does not process rows
 * @param asyncMs writing the state to the checkpoint storage, while the task goes on
 * @param alignmentMs waiting for the barrier on every input
 * @param stateBytes the size of the task's state
 */
public record CheckpointTaskMetrics(long syncMs, long asyncMs, long alignmentMs, long stateBytes) {

    public static final CheckpointTaskMetrics NONE = new CheckpointTaskMetrics(0, 0, 0, 0);
}
