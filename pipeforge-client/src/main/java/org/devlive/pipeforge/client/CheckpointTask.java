/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

/**
 * A task's part of a checkpoint.
 *
 * @param task the task, {@code run/vertex/subtask/attempt}
 * @param states how many states it wrote
 * @param syncMs how long its snapshot held the task up
 * @param asyncMs how long its upload took
 * @param alignmentMs how long it waited for the barriers of its inputs
 * @param stateBytes the size of its states
 */
public record CheckpointTask(String task, int states, long syncMs, long asyncMs, long alignmentMs, long stateBytes) {}
