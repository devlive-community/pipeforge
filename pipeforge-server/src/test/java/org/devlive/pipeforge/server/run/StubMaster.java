/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.util.List;
import java.util.Optional;

/** A master for tests that knows nothing; tests override what they need. */
class StubMaster implements MasterClient {

    @Override
    public String address() {
        return "master:1";
    }

    @Override
    public void submit(Submission submission) {}

    @Override
    public void cancel(String runId, String reason) {}

    @Override
    public Optional<Status> status(String runId) {
        return Optional.empty();
    }

    @Override
    public Optional<RunMetrics> metrics(String runId) {
        return Optional.empty();
    }

    @Override
    public ClusterView cluster() {
        return ClusterView.of(new ClusterView.Master(address(), "test", "1.0", java.time.Instant.EPOCH), List.of());
    }

    @Override
    public Optional<String> analysis(String runId) {
        return Optional.empty();
    }

    @Override
    public Optional<List<RunMetrics.Split>> splits(String runId) {
        return Optional.empty();
    }

    @Override
    public RunMetrics.LogChunk readLog(String runId, RunMetrics.Task task, LogQuery query) {
        return new RunMetrics.LogChunk(0, "", true);
    }

    @Override
    public void close() {}
}
