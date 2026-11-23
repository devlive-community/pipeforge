/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.ReaderMessage;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;

/**
 * The job masters of a master: routes what tasks report to the job master of their run, and tells
 * every job master when a worker is lost. Messages of runs that ended are dropped.
 */
public final class JobMasters implements JobMasterGateway, ResourceManager.Listener {

    private final Map<String, JobMaster> runs = new ConcurrentHashMap<>();

    public void add(JobMaster job) {
        runs.put(job.runId(), job);
    }

    public void remove(String runId) {
        runs.remove(runId);
    }

    public Collection<JobMaster> running() {
        return List.copyOf(runs.values());
    }

    @Override
    public CompletableFuture<Void> updateTaskState(TaskStateUpdate update) {
        JobMaster job = runs.get(update.id().runId());
        if (job != null) {
            job.onTaskState(update);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> reportMetrics(ExecutionAttemptId task, TaskMetricsSnapshot metrics) {
        JobMaster job = runs.get(task.runId());
        if (job != null) {
            job.onMetrics(task, metrics);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendReaderMessage(ReaderMessage message) {
        JobMaster job = runs.get(message.task().runId());
        if (job != null) {
            job.onReaderMessage(message);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> acknowledgeCheckpoint(org.devlive.pipeforge.engine.rpc.CheckpointAck ack) {
        JobMaster job = runs.get(ack.task().runId());
        if (job != null) {
            job.onCheckpointAck(ack);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> declineCheckpoint(
            ExecutionAttemptId task, long checkpointId, org.devlive.pipeforge.common.error.PipeForgeException reason) {
        JobMaster job = runs.get(task.runId());
        if (job != null) {
            job.onCheckpointDecline(task, checkpointId, reason);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void workerLost(ResourceManager.WorkerLost lost) {
        runs.values().forEach(job -> job.workerLost(lost.workerId(), lost.error()));
    }
}
