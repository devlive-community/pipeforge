/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The records of runs the server keeps, as the API shows them. */
public final class Runs {

    private Runs() {}

    /**
     * A run.
     *
     * @param id the run ID
     * @param job the job's name
     * @param status where the run is
     * @param submittedBy who submitted it; empty until the server has users
     * @param error why the run failed, for runs that ended without success
     * @param rowsRead rows the sources read, as of the last sample of the run's metrics; {@code
     *     null} before the first sample
     * @param rowsWritten rows the sinks wrote, as of the same sample
     */
    public record Run(
            String id,
            String job,
            RunState status,
            @Nullable String submittedBy,
            Instant submittedAt,
            @Nullable Instant startedAt,
            @Nullable Instant endedAt,
            Instant updatedAt,
            @Nullable RunError error,
            @Nullable Long rowsRead,
            @Nullable Long rowsWritten) {}

    /** An error of a run: its code and its message in English; the code's page tells more. */
    public record RunError(String code, String message) {}

    /**
     * A change of a run's state.
     *
     * @param from the state before; {@code null} when the run was submitted
     * @param reason why it changed, e.g. the error code of a failure
     */
    public record Transition(int attempt, @Nullable RunState from, RunState to, Instant at, @Nullable String reason) {}

    /** A run with its job definition and every change of its state, oldest first. */
    public record RunDetail(Run run, String definition, List<Transition> transitions) {}

    /**
     * A page of runs, newest first.
     *
     * @param next the token of the next page; {@code null} on the last page
     */
    public record RunPage(List<Run> runs, @Nullable String next) {}
}
