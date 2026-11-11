/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.api.AnalyzeText;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.opentest4j.AssertionFailedError;

/**
 * The outcome of a test job.
 *
 * @param job the job name
 * @param result how the job ended
 */
public record TestRun(String job, JobResult result) {

    public JobStatus status() {
        return result.status();
    }

    public JobMetrics metrics() {
        return result.metrics();
    }

    /** Fails with the job's error, its hint and the run analysis unless the job succeeded. */
    public TestRun requireSuccess() {
        if (result.status() != JobStatus.SUCCESS) {
            PipeForgeException error = result.error();
            String message = "Job " + job + " ended with " + result.status()
                    + (error == null ? "" : ": " + error.getMessage() + "\nHint: " + error.hint("en-US"))
                    + "\n" + AnalyzeText.format(job, result.status(), result.metrics());
            throw new AssertionFailedError(message, error);
        }
        return this;
    }

    /** Fails unless the row counters satisfy the reconciliation identity. */
    public TestRun requireReconciled() {
        if (!result.metrics().reconciled()) {
            throw new AssertionFailedError("The row counters of job " + job + " do not reconcile:\n"
                    + AnalyzeText.format(job, result.status(), result.metrics()));
        }
        return this;
    }
}
