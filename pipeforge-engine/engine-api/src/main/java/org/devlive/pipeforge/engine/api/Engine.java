/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import org.devlive.pipeforge.api.factory.EngineKind;
import org.devlive.pipeforge.core.plan.JobPlan;

/**
 * An execution engine as the control plane sees it. The server, the command line and tests depend
 * only on this interface; the native engine runs locally or on a cluster, and later milestones add
 * engines on Flink and Spark.
 */
public interface Engine extends AutoCloseable {

    EngineKind kind();

    /** What the engine supports, so that callers can reject a job before submitting it. */
    EngineCapabilities capabilities();

    /**
     * Starts a job. Returns as soon as the job is accepted; follow it through the handle.
     *
     * @throws org.devlive.pipeforge.common.error.PipeForgeException when the engine cannot run the
     *     plan, e.g. {@link org.devlive.pipeforge.common.error.ErrorCode#TOPOLOGY_UNSUPPORTED}
     */
    JobHandle submit(JobPlan plan, SubmitOptions options);

    default JobHandle submit(JobPlan plan) {
        return submit(plan, SubmitOptions.defaults());
    }

    /** The plan of a job as the engine would run it, without running it. */
    ExplainResult explain(JobPlan plan);

    /** Cancels the running jobs and releases the engine's threads. */
    @Override
    void close();
}
