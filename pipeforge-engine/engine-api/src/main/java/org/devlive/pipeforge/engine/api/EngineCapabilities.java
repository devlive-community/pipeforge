/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

/**
 * What an engine supports.
 *
 * @param batch runs jobs whose sources all end
 * @param streaming runs jobs with sources that never end; they run until cancelled
 * @param exactlyOnce takes checkpoints and commits sinks in two phases between them
 * @param savepoints can stop a job with a savepoint and resume it from one
 * @param regionRecovery restarts only the failed part of a job
 */
public record EngineCapabilities(
        boolean batch, boolean streaming, boolean exactlyOnce, boolean savepoints, boolean regionRecovery) {}
