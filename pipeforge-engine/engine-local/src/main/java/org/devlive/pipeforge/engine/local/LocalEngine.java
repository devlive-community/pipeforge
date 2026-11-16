/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.devlive.pipeforge.api.factory.EngineKind;
import org.devlive.pipeforge.core.explain.PlanExplainer;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.api.Engine;
import org.devlive.pipeforge.engine.api.EngineCapabilities;
import org.devlive.pipeforge.engine.api.ExplainResult;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobId;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.SubmitOptions;

/**
 * The native engine in one process: a master and one or more workers, connected the way a cluster
 * is — services over in-process gRPC, registration and heartbeats, slots reserved by the scheduler,
 * tasks that exchange rows through the data exchange of their worker (in memory with one worker,
 * over TCP on the loopback between several).
 *
 * <p>A job is a pipeline: one source, its transforms and one sink in a line, with any parallelism
 * per node. Checkpoints and restarts come with the distributed engine.
 */
public final class LocalEngine implements Engine {

    /** The system property that sets the number of workers of {@link #LocalEngine()}. */
    public static final String WORKERS = "pipeforge.local.workers";

    private final LocalCluster cluster;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    /** With the number of workers of {@value #WORKERS}, 1 when it is not set. */
    public LocalEngine() {
        this(Integer.getInteger(WORKERS, 1));
    }

    /** With {@code workers} workers in this process. */
    public LocalEngine(int workers) {
        cluster = new LocalCluster(workers);
    }

    /** The number of workers that are registered. */
    public int workers() {
        return cluster.workers();
    }

    @Override
    public EngineKind kind() {
        return EngineKind.NATIVE;
    }

    @Override
    public EngineCapabilities capabilities() {
        return new EngineCapabilities(true, true, false, false, false);
    }

    @Override
    public JobHandle submit(JobPlan plan, SubmitOptions options) {
        return cluster.submit(JobId.random().value(), plan, options);
    }

    @Override
    public ExplainResult explain(JobPlan plan) {
        return new ExplainResult(PlanExplainer.document(plan), PlanExplainer.text(plan));
    }

    LocalCluster cluster() {
        return cluster;
    }

    /**
     * Cancels the running jobs, waits up to 10 seconds for them to end and stops the threads;
     * closing it again does nothing.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<CompletableFuture<JobResult>> ending = cluster.jobs().running().stream()
                .map(job -> {
                    job.cancel();
                    return job.handle().result();
                })
                .toList();
        try {
            CompletableFuture.allOf(ending.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException e) {
            // stop anyway
        }
        cluster.close();
    }
}
