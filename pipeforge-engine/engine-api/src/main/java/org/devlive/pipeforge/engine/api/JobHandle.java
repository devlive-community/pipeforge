/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.concurrent.CompletableFuture;

/** A submitted job. All methods are thread-safe. */
public interface JobHandle {

    JobId id();

    /** The name of the job, as in its definition. */
    String name();

    JobStatus status();

    /** A snapshot of the job's metrics; cheap to call repeatedly. */
    JobMetrics metrics();

    /** Completes when the job ends, with its final status, error and metrics; never completes exceptionally. */
    CompletableFuture<JobResult> result();

    /** Asks the job to stop. Returns at once; {@link #result()} completes with {@link JobStatus#CANCELLED}. */
    void cancel();
}
