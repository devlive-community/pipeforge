/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import java.util.List;
import java.util.UUID;
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
import org.devlive.pipeforge.engine.master.ChainTopology;
import org.devlive.pipeforge.engine.master.JobMaster;
import org.devlive.pipeforge.engine.master.JobMasters;
import org.devlive.pipeforge.engine.master.Placement;
import org.devlive.pipeforge.engine.master.graph.JobGraph;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.network.NetworkOptions;
import org.devlive.pipeforge.engine.rpc.InProcessRpc;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.worker.TaskExecutor;

/**
 * The native engine in one process: a master with one job master per job and a worker with a task
 * executor, connected by the in-process implementation of the same RPC interfaces a cluster uses.
 *
 * <p>A job is a pipeline: one source, its transforms and one sink in a line; each parallel instance
 * of a chain is a task on its own thread, and where the parallelism changes the tasks exchange
 * rows in memory, with the same buffers and credit as between workers. Checkpoints and restarts
 * come with the distributed engine.
 */
public final class LocalEngine implements Engine {

    private final JobMasters jobs = new JobMasters();
    private final InProcessRpc.Endpoint<JobMasterGateway> master;
    private final NetworkEnvironment network;
    private final TaskExecutor taskExecutor;
    private final InProcessRpc.Endpoint<TaskExecutorGateway> worker;

    public LocalEngine() {
        master = InProcessRpc.serve("master", jobs);
        network = new NetworkEnvironment("local", UUID.randomUUID().toString(), NetworkOptions.defaults());
        network.startLocal();
        taskExecutor = new TaskExecutor("local", master.gateway(), null, network);
        worker = InProcessRpc.serve("worker", taskExecutor);
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
        JobGraph graph = ChainTopology.graph(plan);
        JobId id = JobId.random();
        JobMaster job = new JobMaster(
                id, plan, graph, options, Placement.local(worker.gateway()), () -> jobs.remove(id.value()));
        jobs.add(job);
        job.start();
        return job.handle();
    }

    @Override
    public ExplainResult explain(JobPlan plan) {
        return new ExplainResult(PlanExplainer.document(plan), PlanExplainer.text(plan));
    }

    /** Cancels the running jobs, waits up to 10 seconds for them to end and stops the threads. */
    @Override
    public void close() {
        List<CompletableFuture<JobResult>> ending = jobs.running().stream()
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
        taskExecutor.close();
        worker.close();
        master.close();
        network.close();
    }
}
