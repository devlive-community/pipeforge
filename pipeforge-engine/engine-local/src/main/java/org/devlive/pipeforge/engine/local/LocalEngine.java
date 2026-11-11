/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.devlive.pipeforge.api.factory.EngineKind;
import org.devlive.pipeforge.core.explain.PlanExplainer;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.api.Engine;
import org.devlive.pipeforge.engine.api.EngineCapabilities;
import org.devlive.pipeforge.engine.api.ExplainResult;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobId;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.InProcessRpc;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.ReaderMessage;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.worker.TaskExecutor;

/**
 * The native engine in one process: a master with one job master per job and a worker with a task
 * executor, connected by the in-process implementation of the same RPC interfaces a cluster uses.
 *
 * <p>Every forward-connected job runs: one source, its transforms and one sink in a line, with the
 * same parallelism; each parallel instance is a task on its own thread. Checkpoints, restarts and
 * data exchange between tasks come with the distributed engine.
 */
public final class LocalEngine implements Engine {

    private final Map<String, LocalJobMaster> jobs = new ConcurrentHashMap<>();
    private final InProcessRpc.Endpoint<JobMasterGateway> master;
    private final TaskExecutor taskExecutor;
    private final InProcessRpc.Endpoint<TaskExecutorGateway> worker;

    public LocalEngine() {
        master = InProcessRpc.serve("master", new Router());
        taskExecutor = new TaskExecutor("local", master.gateway());
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
        List<PlannedNode> chain = LocalTopology.chain(plan);
        JobId id = JobId.random();
        LocalJobMaster job =
                new LocalJobMaster(id, plan, chain, options, worker.gateway(), () -> jobs.remove(id.value()));
        jobs.put(job.runId(), job);
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
        List<CompletableFuture<JobResult>> ending = jobs.values().stream()
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
    }

    /** The master endpoint: routes the messages of the tasks to the job master of their run. */
    private final class Router implements JobMasterGateway {
        @Override
        public CompletableFuture<Void> updateTaskState(TaskStateUpdate update) {
            LocalJobMaster job = jobs.get(update.id().runId());
            if (job != null) {
                job.onTaskState(update);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> reportMetrics(ExecutionAttemptId task, TaskMetricsSnapshot metrics) {
            LocalJobMaster job = jobs.get(task.runId());
            if (job != null) {
                job.onMetrics(task, metrics);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendReaderMessage(ReaderMessage message) {
            LocalJobMaster job = jobs.get(message.task().runId());
            if (job != null) {
                job.onReaderMessage(message);
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
