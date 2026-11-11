/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.Objects;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * How a job ended.
 *
 * @param id the job
 * @param status {@link JobStatus#SUCCESS}, {@link JobStatus#FAILED} or {@link JobStatus#CANCELLED}
 * @param error the error that failed the job; {@code null} unless the status is FAILED
 * @param metrics the final metrics
 */
public record JobResult(JobId id, JobStatus status, @Nullable PipeForgeException error, JobMetrics metrics) {

    public JobResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metrics, "metrics");
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("A job result needs a final status, not " + status);
        }
        if ((status == JobStatus.FAILED) != (error != null)) {
            throw new IllegalArgumentException("A job result has an error exactly when it failed");
        }
    }
}
