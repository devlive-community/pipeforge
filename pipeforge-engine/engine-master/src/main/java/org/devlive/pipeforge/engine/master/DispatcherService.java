/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.api.NodeMetrics;
import org.devlive.pipeforge.engine.master.RunStates.State;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.RpcErrors;
import org.devlive.pipeforge.engine.rpc.proto.Ack;
import org.devlive.pipeforge.engine.rpc.proto.DispatcherServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.Header;
import org.devlive.pipeforge.engine.rpc.proto.JobRequest;
import org.devlive.pipeforge.engine.rpc.proto.JobStatus;
import org.devlive.pipeforge.engine.rpc.proto.ListJobsRequest;
import org.devlive.pipeforge.engine.rpc.proto.ListJobsResponse;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.engine.rpc.proto.RunState;
import org.devlive.pipeforge.engine.rpc.proto.SubmitJobRequest;
import org.devlive.pipeforge.engine.rpc.proto.SubmitJobResponse;
import org.devlive.pipeforge.engine.rpc.proto.TaskStatus;

/**
 * The gRPC side of the {@link Dispatcher}: the server submits, cancels, lists and watches runs,
 * reads their analysis and splits, and reads task logs, which the master forwards from the worker
 * the task ran on (protocol specification, section 2.1).
 */
public final class DispatcherService extends DispatcherServiceGrpc.DispatcherServiceImplBase {

    /** Runs a page lists when the request does not say. */
    static final int DEFAULT_PAGE = 100;

    private final Dispatcher dispatcher;
    private final java.util.function.Supplier<List<ResourceManager.Worker>> workers;
    private final Instant started = Instant.now();
    private final Control control;

    /** A dispatcher without registered workers, as in one process. */
    public DispatcherService(Dispatcher dispatcher) {
        this(dispatcher, List::of);
    }

    /** @param workers the workers of the cluster, from the {@link ResourceManager} */
    public DispatcherService(Dispatcher dispatcher, java.util.function.Supplier<List<ResourceManager.Worker>> workers) {
        this(dispatcher, workers, Control.NONE);
    }

    /**
     * @param control drains workers and hands the lead over, for {@code pipeforge cluster drain} and
     *     {@code failover}
     */
    public DispatcherService(
            Dispatcher dispatcher, java.util.function.Supplier<List<ResourceManager.Worker>> workers, Control control) {
        this.dispatcher = dispatcher;
        this.workers = workers;
        this.control = control;
    }

    /** What an operator changes about the cluster through the master. */
    public interface Control {
        /** Neither drains nor hands over: a master that is not part of a cluster with standbys. */
        Control NONE = new Control() {
            @Override
            public void drain(String workerId) {
                throw org.devlive.pipeforge.common.error.PipeForgeException.of(
                                org.devlive.pipeforge.common.error.ErrorCode.WORKER_NOT_FOUND)
                        .param("worker", workerId)
                        .build();
            }

            @Override
            public void uncordon(String workerId) {
                drain(workerId);
            }

            @Override
            public void releaseLeadership() {
                throw org.devlive.pipeforge.common.error.PipeForgeException.of(
                                org.devlive.pipeforge.common.error.ErrorCode.FAILOVER_UNAVAILABLE)
                        .param("master", "this master")
                        .build();
            }
        };

        /** Gives a worker no new tasks ({@link ResourceManager#drain}). */
        void drain(String workerId);

        /** Gives a drained worker new tasks again ({@link ResourceManager#uncordon}). */
        void uncordon(String workerId);

        /** Gives the lease up so that a standby takes over; the master stops after it answered. */
        void releaseLeadership();
    }

    @Override
    public void drainWorker(
            org.devlive.pipeforge.engine.rpc.proto.WorkerRequest request,
            StreamObserver<org.devlive.pipeforge.engine.rpc.proto.Ack> observer) {
        answer(observer, request.getHeader(), () -> {
            control.drain(request.getWorkerId());
            return org.devlive.pipeforge.engine.rpc.proto.Ack.getDefaultInstance();
        });
    }

    @Override
    public void uncordonWorker(
            org.devlive.pipeforge.engine.rpc.proto.WorkerRequest request,
            StreamObserver<org.devlive.pipeforge.engine.rpc.proto.Ack> observer) {
        answer(observer, request.getHeader(), () -> {
            control.uncordon(request.getWorkerId());
            return org.devlive.pipeforge.engine.rpc.proto.Ack.getDefaultInstance();
        });
    }

    @Override
    public void releaseLeadership(
            org.devlive.pipeforge.engine.rpc.proto.ClusterRequest request,
            StreamObserver<org.devlive.pipeforge.engine.rpc.proto.Ack> observer) {
        answer(observer, request.getHeader(), () -> {
            control.releaseLeadership();
            return org.devlive.pipeforge.engine.rpc.proto.Ack.getDefaultInstance();
        });
    }

    /** The version in the manifest of the engine jar, or {@code dev} when run from classes. */
    static String engineVersion() {
        String version = DispatcherService.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    @Override
    public void getCluster(
            org.devlive.pipeforge.engine.rpc.proto.ClusterRequest request,
            StreamObserver<org.devlive.pipeforge.engine.rpc.proto.ClusterStatus> observer) {
        answer(observer, request.getHeader(), () -> {
            var status = org.devlive.pipeforge.engine.rpc.proto.ClusterStatus.newBuilder()
                    .setEngineVersion(engineVersion())
                    .setProtocolVersion(org.devlive.pipeforge.engine.rpc.ProtocolVersion.CURRENT.toString())
                    .setStartedMs(started.toEpochMilli());
            for (ResourceManager.Worker worker : workers.get()) {
                ResourceManager.WorkerSpec spec = worker.spec();
                var info = org.devlive.pipeforge.engine.rpc.proto.WorkerInfo.newBuilder()
                        .setWorkerId(worker.id())
                        .setEngineVersion(spec.engineVersion())
                        .setRpcAddress(spec.rpcAddress())
                        .setDataAddress(spec.dataAddress())
                        .setResources(org.devlive.pipeforge.engine.rpc.proto.ResourceProfile.newBuilder()
                                .setCpuCores(spec.resources().cpuCores())
                                .setTaskHeapBytes(spec.resources().taskHeapBytes())
                                .setNetworkBytes(spec.resources().networkBytes())
                                .setManagedBytes(spec.resources().managedBytes()))
                        .putAllLabels(spec.labels())
                        .setResourceGroup(spec.resourceGroup())
                        .setTasksRunning((int) worker.tasks().stream()
                                .filter(t -> t.state() == org.devlive.pipeforge.engine.rpc.TaskState.RUNNING)
                                .count())
                        .setSinceHeartbeatMs(worker.sinceHeartbeat().toMillis())
                        .setDraining(worker.draining());
                for (ResourceManager.Slot slot : worker.slots()) {
                    var proto = org.devlive.pipeforge.engine.rpc.proto.SlotStatus.newBuilder()
                            .setIndex(slot.index())
                            .setAllocated(slot.allocated());
                    if (slot.jobId() != null) {
                        proto.setJobId(slot.jobId());
                    }
                    if (slot.allocationId() != null) {
                        proto.setAllocationId(slot.allocationId());
                    }
                    info.addSlots(proto);
                }
                status.addWorkers(info);
            }
            return status.build();
        });
    }

    @Override
    public void submitJob(SubmitJobRequest request, StreamObserver<SubmitJobResponse> observer) {
        answer(observer, request.getHeader(), () -> {
            Map<String, String> plugins = new LinkedHashMap<>();
            for (PluginRef plugin : request.getPluginsList()) {
                plugins.put(plugin.getId(), plugin.getVersion());
            }
            var status = dispatcher.submit(new Dispatcher.Submission(
                    request.getJobId(),
                    request.getRunId(),
                    request.getDefinition(),
                    request.getVariablesMap(),
                    request.getScheduledTimeMs() == 0 ? null : Instant.ofEpochMilli(request.getScheduledTimeMs()),
                    plugins,
                    request.getTimeoutMs() == 0 ? null : Duration.ofMillis(request.getTimeoutMs()),
                    request.hasRestore() && !request.getRestore().getPath().isEmpty()
                            ? new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                                    request.getRestore().getPath(),
                                    request.getRestore().getAllowNonRestoredState(),
                                    request.getRestore().getAllowOtherJob())
                            : null,
                    Math.max(1, request.getAttempt())));
            return SubmitJobResponse.newBuilder().setRunId(status.runId()).build();
        });
    }

    @Override
    public void stopWithSavepoint(
            org.devlive.pipeforge.engine.rpc.proto.SavepointRequest request,
            StreamObserver<org.devlive.pipeforge.engine.rpc.proto.SavepointResponse> observer) {
        java.util.concurrent.CompletableFuture<
                        org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Completed>
                pausing;
        try {
            Headers.checkVersion(request.getHeader());
            pausing = dispatcher.pause(request.getRunId(), request.getName().isEmpty() ? null : request.getName());
        } catch (RuntimeException e) {
            pausing = java.util.concurrent.CompletableFuture.failedFuture(e);
        }
        respond(observer, pausing);
    }

    /** Answers with a savepoint once it completed, or with why it did not. */
    private static void respond(
            StreamObserver<org.devlive.pipeforge.engine.rpc.proto.SavepointResponse> observer,
            java.util.concurrent.CompletableFuture<
                            org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Completed>
                    taking) {
        taking.whenComplete((completed, error) -> {
            if (error != null) {
                observer.onError(RpcErrors.toStatus(
                        error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                                ? error.getCause()
                                : error));
            } else {
                observer.onNext(org.devlive.pipeforge.engine.rpc.proto.SavepointResponse.newBuilder()
                        .setCheckpointId(completed.checkpointId())
                        .setPath(completed.directory())
                        .build());
                observer.onCompleted();
            }
        });
    }

    @Override
    public void triggerSavepoint(
            org.devlive.pipeforge.engine.rpc.proto.SavepointRequest request,
            StreamObserver<org.devlive.pipeforge.engine.rpc.proto.SavepointResponse> observer) {
        java.util.concurrent.CompletableFuture<
                        org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Completed>
                taking;
        try {
            Headers.checkVersion(request.getHeader());
            taking = dispatcher.triggerSavepoint(
                    request.getRunId(), request.getName().isEmpty() ? null : request.getName());
        } catch (RuntimeException e) {
            taking = java.util.concurrent.CompletableFuture.failedFuture(e);
        }
        respond(observer, taking);
    }

    @Override
    public void cancelJob(JobRequest request, StreamObserver<Ack> observer) {
        answer(observer, request.getHeader(), () -> {
            dispatcher.cancel(
                    request.getRunId(), request.getReason().isEmpty() ? "cancel requested" : request.getReason());
            return Ack.getDefaultInstance();
        });
    }

    @Override
    public void getJobStatus(JobRequest request, StreamObserver<JobStatus> observer) {
        answer(observer, request.getHeader(), () -> toProto(dispatcher.status(request.getRunId())));
    }

    @Override
    public void getJobMetrics(
            JobRequest request, StreamObserver<org.devlive.pipeforge.engine.rpc.proto.JobMetrics> observer) {
        answer(
                observer,
                request.getHeader(),
                () -> toProto(request.getRunId(), dispatcher.metrics(request.getRunId())));
    }

    @Override
    public void analyze(
            JobRequest request, StreamObserver<org.devlive.pipeforge.engine.rpc.proto.ExplainResult> observer) {
        answer(observer, request.getHeader(), () -> {
            var analysis = dispatcher.analysis(request.getRunId());
            return org.devlive.pipeforge.engine.rpc.proto.ExplainResult.newBuilder()
                    .setSchemaVersion(analysis.schemaVersion())
                    .setJson(analysis.json())
                    .build();
        });
    }

    @Override
    public void listJobs(ListJobsRequest request, StreamObserver<ListJobsResponse> observer) {
        answer(observer, request.getHeader(), () -> {
            Set<State> states = EnumSet.noneOf(State.class);
            request.getStatesList().forEach(state -> states.add(fromProto(state)));
            int offset = request.getPageToken().isEmpty() ? 0 : Integer.parseInt(request.getPageToken());
            int limit = request.getLimit() <= 0 ? DEFAULT_PAGE : request.getLimit();
            List<Dispatcher.RunStatus> all = dispatcher.list(states, Integer.MAX_VALUE);
            var response = ListJobsResponse.newBuilder();
            all.stream().skip(offset).limit(limit).forEach(run -> response.addJobs(toProto(run)));
            if (offset + limit < all.size()) {
                response.setNextPageToken(String.valueOf(offset + limit));
            }
            return response.build();
        });
    }

    @Override
    public void listSplits(
            JobRequest request, StreamObserver<org.devlive.pipeforge.engine.rpc.proto.ListSplitsResponse> observer) {
        answer(observer, request.getHeader(), () -> {
            var response = org.devlive.pipeforge.engine.rpc.proto.ListSplitsResponse.newBuilder();
            for (JobMaster.SplitView split : dispatcher.splits(request.getRunId())) {
                response.addSplits(org.devlive.pipeforge.engine.rpc.proto.SplitInfo.newBuilder()
                        .setNodeId(split.node())
                        .setSplitId(split.split())
                        .setSubtask(split.subtask())
                        .setState(
                                split.finished()
                                        ? org.devlive.pipeforge.engine.rpc.proto.SplitState.SPLIT_STATE_FINISHED
                                        : org.devlive.pipeforge.engine.rpc.proto.SplitState.SPLIT_STATE_ASSIGNED));
            }
            return response.build();
        });
    }

    /** Streams the log of a task from its worker; a client that stops following cancels the read. */
    @Override
    public void readTaskLog(
            org.devlive.pipeforge.engine.rpc.proto.ReadTaskLogRequest request,
            StreamObserver<org.devlive.pipeforge.engine.rpc.proto.LogChunk> observer) {
        java.util.concurrent.CompletableFuture<Void> reading;
        try {
            Headers.checkVersion(request.getHeader());
            var query = new org.devlive.pipeforge.engine.rpc.LogQuery(
                    request.getOffset(),
                    request.getMaxBytes(),
                    request.getContains(),
                    request.getMinLevel().isEmpty() ? null : request.getMinLevel(),
                    request.getFollow());
            reading =
                    dispatcher.readLog(request.getRunId(), ProtoMapping.fromProto(request.getTask()), query, chunk -> {
                        synchronized (observer) {
                            observer.onNext(ProtoMapping.toProto(chunk));
                        }
                    });
        } catch (RuntimeException e) {
            reading = java.util.concurrent.CompletableFuture.failedFuture(e);
        }
        java.util.concurrent.CompletableFuture<Void> running = reading;
        if (observer instanceof io.grpc.stub.ServerCallStreamObserver<?> call) {
            call.setOnCancelHandler(() -> running.cancel(false));
        }
        running.whenComplete((ignored, error) -> {
            synchronized (observer) {
                if (error instanceof java.util.concurrent.CancellationException) {
                    return;
                }
                if (error != null) {
                    observer.onError(RpcErrors.toStatus(error));
                } else {
                    observer.onCompleted();
                }
            }
        });
    }

    private static <T> void answer(StreamObserver<T> observer, Header header, Supplier<T> work) {
        T result;
        try {
            Headers.checkVersion(header);
            result = work.get();
        } catch (RuntimeException e) {
            observer.onError(RpcErrors.toStatus(e));
            return;
        }
        observer.onNext(result);
        observer.onCompleted();
    }

    static JobStatus toProto(Dispatcher.RunStatus run) {
        var status = JobStatus.newBuilder()
                .setRunId(run.runId())
                .setJobId(run.jobId())
                .setJobName(run.jobName())
                .setState(toProto(run.state()))
                .setStartMs(run.started().toEpochMilli())
                .setAttempt(run.attempt())
                .setLastCheckpointId(run.lastCheckpointId())
                .setLastCheckpointMs(run.lastCheckpointMs());
        if (run.ended() != null) {
            status.setEndMs(run.ended().toEpochMilli());
        }
        if (run.error() != null) {
            status.setError(ProtoMapping.toProto(run.error()));
        }
        for (JobMaster.TaskView task : run.tasks()) {
            status.addTasks(TaskStatus.newBuilder()
                    .setId(ProtoMapping.toProto(task.attempt()))
                    .setState(ProtoMapping.toProto(task.state()))
                    .setWorkerId(task.workerId()));
        }
        return status.build();
    }

    static org.devlive.pipeforge.engine.rpc.proto.JobMetrics toProto(String runId, JobMetrics metrics) {
        var proto = org.devlive.pipeforge.engine.rpc.proto.JobMetrics.newBuilder()
                .setRunId(runId)
                .setTimestampMs(System.currentTimeMillis())
                .setSplitsTotal(metrics.splitsAssigned())
                .setSplitsFinished(metrics.splitsFinished())
                .setElapsedMs(metrics.elapsedMs())
                .setRowsRead(metrics.rowsRead())
                .setRowsWritten(metrics.rowsWritten())
                .setRowsFiltered(metrics.rowsFiltered())
                .setRowsExpanded(metrics.rowsExpanded())
                .setRowsFailed(metrics.rowsFailed())
                .setRowsInFlight(metrics.rowsInFlight())
                .setReadRowsPerSecond(metrics.readRowsPerSecond().current())
                .setWriteRowsPerSecond(metrics.writeRowsPerSecond().current())
                .setReaderWaitMs(metrics.readerWaitMs())
                .setWriterWaitMs(metrics.writerWaitMs())
                .setBackPressuredMs(metrics.backpressuredMs())
                .setExchangedBytes(metrics.exchangedBytes())
                .setQueueDepth(metrics.queueDepth())
                .setQueueCapacity(metrics.queueCapacity())
                .setTasksRunning(metrics.tasksRunning())
                .setParallelism(metrics.parallelism())
                .setMemoryBytes(metrics.memoryBytes())
                .setRowsDirty(metrics.rowsDirty())
                .setThrottleWaitMs(metrics.throttleWaitMs())
                .setLastCompletedCheckpointId(metrics.lastCompletedCheckpointId())
                .setLastCheckpointDurationMs(metrics.lastCheckpointDurationMs())
                .setLastCheckpointSize(metrics.lastCheckpointSize())
                .setCheckpointFailures(metrics.checkpointFailures());
        metrics.dirtySamples().forEach(sample -> proto.addDirtySamples(ProtoMapping.toProto(sample)));
        for (NodeMetrics node : metrics.nodes()) {
            proto.addNodes(org.devlive.pipeforge.engine.rpc.proto.NodeMetrics.newBuilder()
                    .setNodeId(node.node())
                    .setParallelism(metrics.parallelism())
                    .setRowsIn(node.rowsIn())
                    .setRowsOut(node.rowsOut())
                    .setRowsFiltered(node.rowsFiltered())
                    .setRowsExpanded(node.rowsExpanded())
                    .setRowsDirty(node.rowsDirty())
                    .setBytesIn(node.bytesIn())
                    .setBytesOut(node.bytesOut()));
        }
        return proto.build();
    }

    static RunState toProto(State state) {
        return switch (state) {
            case QUEUE -> RunState.RUN_STATE_QUEUE;
            case RUNNING -> RunState.RUN_STATE_RUNNING;
            case CANCELLING -> RunState.RUN_STATE_CANCELLING;
            case SUCCESS -> RunState.RUN_STATE_SUCCESS;
            case FAILED -> RunState.RUN_STATE_FAILED;
            case CANCELLED -> RunState.RUN_STATE_CANCELLED;
            case TIMEOUT -> RunState.RUN_STATE_TIMEOUT;
            case PAUSING -> RunState.RUN_STATE_PAUSING;
            case PAUSED -> RunState.RUN_STATE_PAUSED;
        };
    }

    static State fromProto(RunState state) {
        return switch (state) {
            case RUN_STATE_QUEUE -> State.QUEUE;
            case RUN_STATE_RUNNING -> State.RUNNING;
            case RUN_STATE_CANCELLING -> State.CANCELLING;
            case RUN_STATE_SUCCESS -> State.SUCCESS;
            case RUN_STATE_FAILED -> State.FAILED;
            case RUN_STATE_CANCELLED -> State.CANCELLED;
            case RUN_STATE_TIMEOUT -> State.TIMEOUT;
            case RUN_STATE_PAUSING -> State.PAUSING;
            case RUN_STATE_PAUSED -> State.PAUSED;
                // states of run retries; this master does not retry runs
            case RUN_STATE_RESTARTING,
                    RUN_STATE_RETRY_WAIT,
                    RUN_STATE_UNSPECIFIED,
                    UNRECOGNIZED -> throw new IllegalArgumentException("Unknown run state " + state);
        };
    }
}
