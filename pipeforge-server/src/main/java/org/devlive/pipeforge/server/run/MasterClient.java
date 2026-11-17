/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/** What the server asks the master's dispatcher. */
public interface MasterClient extends AutoCloseable {

    /**
     * A run to start.
     *
     * @param plugins plugin ID to the version the server planned with
     */
    record Submission(
            String jobId,
            String runId,
            String definition,
            Map<String, String> variables,
            Instant scheduledTime,
            Map<String, String> plugins) {}

    /**
     * Where a run is on the master.
     *
     * @param started when it started; {@code null} before
     * @param error why it failed
     */
    record Status(RunState state, @Nullable Instant started, Runs.@Nullable RunError error) {}

    /** Where the master is, for messages. */
    String address();

    /** Starts a run; submitting the same run ID again does nothing. */
    void submit(Submission submission);

    /** Asks the master to cancel a run. */
    void cancel(String runId, String reason);

    /**
     * The run on the master; empty when the master does not know it.
     *
     * @throws PipeForgeException {@code PF-CLUS-0008} when the master cannot be reached
     */
    Optional<Status> status(String runId);

    @Override
    void close();

    /** The client of a server without a master: every call fails with {@code PF-CLUS-0015}. */
    static MasterClient none() {
        return new MasterClient() {
            @Override
            public String address() {
                return "-";
            }

            @Override
            public void submit(Submission submission) {
                throw missing();
            }

            @Override
            public void cancel(String runId, String reason) {
                throw missing();
            }

            @Override
            public Optional<Status> status(String runId) {
                throw missing();
            }

            @Override
            public void close() {}

            private PipeForgeException missing() {
                return PipeForgeException.of(org.devlive.pipeforge.common.error.ErrorCode.MASTER_NOT_CONFIGURED)
                        .build();
            }
        };
    }
}
