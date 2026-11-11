/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.variable;

import java.time.Instant;
import java.util.Objects;

/**
 * The run a job is planned for, as seen by ${run:...} variables.
 *
 * @param id the run id
 * @param attempt the attempt number, starting at 1
 * @param startTime when the run started
 */
public record RunInfo(String id, int attempt, Instant startTime) {

    public RunInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(startTime, "startTime");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1, got " + attempt);
        }
    }

    /** The run of a plan that is only explained or validated: id {@code preview}, attempt 1. */
    public static RunInfo preview(Instant startTime) {
        return new RunInfo("preview", 1, startTime);
    }
}
