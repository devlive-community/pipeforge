/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The run a task belongs to, as the task description carries it.
 *
 * @param runStart when the run started; the same for every task of the run and across retries, it
 *     is what {@code RUN_START_TIME()} returns in expressions
 * @param scheduledTime the planned time of the run, or {@code null}
 * @param mode {@code batch} or {@code streaming}
 * @param delivery {@code at-least-once} or {@code exactly-once}
 */
public record RunContext(
        String jobId,
        String runId,
        Map<String, String> variables,
        Instant runStart,
        @Nullable Instant scheduledTime,
        String mode,
        String delivery) {

    public RunContext {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runStart, "runStart");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(delivery, "delivery");
        variables = Map.copyOf(variables);
    }

    /** A batch run without variables, as tests and tools start one. */
    public static RunContext of(String runId, Instant runStart) {
        return new RunContext(runId, runId, Map.of(), runStart, null, "batch", "at-least-once");
    }
}
