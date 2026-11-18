/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import org.jspecify.annotations.Nullable;

/**
 * A task of a run and where it runs.
 *
 * @param vertex the vertex
 * @param subtask the subtask
 * @param attempt the attempt
 * @param state its state
 * @param worker the worker it runs on
 */
public record RunTask(String vertex, int subtask, int attempt, String state, @Nullable String worker) {}
