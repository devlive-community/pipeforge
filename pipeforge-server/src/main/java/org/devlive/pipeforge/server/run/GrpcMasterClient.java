/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.RpcErrors;
import org.devlive.pipeforge.engine.rpc.proto.DispatcherServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.proto.JobRequest;
import org.devlive.pipeforge.engine.rpc.proto.JobStatus;
import org.devlive.pipeforge.engine.rpc.proto.LogChunk;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.engine.rpc.proto.ReadTaskLogRequest;
import org.devlive.pipeforge.engine.rpc.proto.SubmitJobRequest;
import org.jspecify.annotations.Nullable;

/** The master's dispatcher over gRPC (protocol: DispatcherService). */
public final class GrpcMasterClient implements MasterClient {

    /** How long a call may take. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AutoCloseable channel;
    private final java.util.function.Supplier<String> address;
    private final DispatcherServiceGrpc.DispatcherServiceBlockingStub stub;

    /** To one master. */
    public GrpcMasterClient(ManagedChannel channel, String address) {
        this.channel = channel::shutdownNow;
        this.address = () -> address;
        this.stub = DispatcherServiceGrpc.newBlockingStub(channel);
    }

    /** To the leader among the masters of a cluster, which the channel follows. */
    public GrpcMasterClient(org.devlive.pipeforge.engine.rpc.grpc.MasterChannel channel) {
        this.channel = channel;
        this.address = channel::address;
        this.stub = DispatcherServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public String address() {
        return address.get();
    }

    private DispatcherServiceGrpc.DispatcherServiceBlockingStub call() {
        return stub.withDeadlineAfter(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static org.devlive.pipeforge.engine.rpc.proto.Header header() {
        return Headers.create(Headers.Epochs.FIRST, TIMEOUT, System::currentTimeMillis);
    }

    @Override
    public void submit(Submission submission) {
        SubmitJobRequest.Builder request = SubmitJobRequest.newBuilder()
                .setHeader(header())
                .setJobId(submission.jobId())
                .setRunId(submission.runId())
                .setDefinition(submission.definition())
                .setDefinitionFormat("yaml")
                .putAllVariables(submission.variables())
                .setScheduledTimeMs(submission.scheduledTime().toEpochMilli())
                .setAttempt(submission.attempt());
        if (submission.timeout() != null) {
            // at least a millisecond: 0 would be the job's whole env.timeout.run
            request.setTimeoutMs(Math.max(1, submission.timeout().toMillis()));
        }
        if (submission.restorePath() != null) {
            request.setRestore(org.devlive.pipeforge.engine.rpc.proto.RestoreSettings.newBuilder()
                    .setPath(submission.restorePath()));
        }
        submission
                .plugins()
                .forEach((id, version) ->
                        request.addPlugins(PluginRef.newBuilder().setId(id).setVersion(version)));
        try {
            call().submitJob(request.build());
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, address());
        }
    }

    @Override
    public void cancel(String runId, String reason) {
        try {
            call().cancelJob(JobRequest.newBuilder()
                    .setHeader(header())
                    .setRunId(runId)
                    .setReason(reason)
                    .build());
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, address());
        }
    }

    @Override
    public Optional<Status> status(String runId) {
        JobStatus status;
        try {
            status = call().getJobStatus(JobRequest.newBuilder()
                    .setHeader(header())
                    .setRunId(runId)
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw RpcErrors.fromStatus(e, address());
        } catch (RuntimeException e) {
            PipeForgeException error = RpcErrors.fromStatus(e, address());
            if (error.code().equals(ErrorCode.RUN_NOT_FOUND)) {
                return Optional.empty();
            }
            throw error;
        }
        Runs.RunError error = status.hasError()
                ? new Runs.RunError(
                        status.getError().getCode(), status.getError().getMessage())
                : null;
        List<RunMetrics.Task> tasks = status.getTasksList().stream()
                .map(task -> new RunMetrics.Task(
                        task.getId().getVertexId(),
                        task.getId().getSubtask(),
                        task.getId().getAttempt(),
                        task.getState().name().replace("TASK_STATE_", ""),
                        task.getWorkerId()))
                .toList();
        return Optional.of(new Status(
                RunState.fromProto(status.getState()),
                status.getStartMs() == 0 ? null : Instant.ofEpochMilli(status.getStartMs()),
                error,
                tasks,
                Math.max(1, status.getAttempt())));
    }

    /** The answer of a call, or empty when the master does not know the run. */
    private <T> Optional<T> known(java.util.function.Supplier<T> call) {
        try {
            return Optional.of(call.get());
        } catch (RuntimeException e) {
            PipeForgeException error = RpcErrors.fromStatus(e, address());
            if (error.code().equals(ErrorCode.RUN_NOT_FOUND)) {
                return Optional.empty();
            }
            throw error;
        }
    }

    @Override
    public Optional<RunMetrics> metrics(String runId) {
        return known(() -> call().getJobMetrics(request(runId))).map(GrpcMasterClient::metrics);
    }

    @Override
    public ClusterView cluster() {
        org.devlive.pipeforge.engine.rpc.proto.ClusterStatus status;
        try {
            status = call().getCluster(org.devlive.pipeforge.engine.rpc.proto.ClusterRequest.newBuilder()
                    .setHeader(header())
                    .build());
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, address());
        }
        List<ClusterView.Worker> workers = status.getWorkersList().stream()
                .map(w -> new ClusterView.Worker(
                        w.getWorkerId(),
                        w.getEngineVersion(),
                        w.getRpcAddress(),
                        w.getDataAddress(),
                        w.getLabelsMap(),
                        w.getResourceGroup(),
                        w.getResources().getCpuCores(),
                        w.getResources().getTaskHeapBytes(),
                        w.getResources().getNetworkBytes(),
                        w.getResources().getManagedBytes(),
                        w.getSlotsList().stream()
                                .map(s -> new ClusterView.Slot(
                                        s.getIndex(),
                                        s.getAllocated(),
                                        s.getJobId().isEmpty() ? null : s.getJobId()))
                                .toList(),
                        w.getTasksRunning(),
                        w.getSinceHeartbeatMs(),
                        w.getDraining()))
                .toList();
        return ClusterView.of(
                new ClusterView.Master(
                        address(),
                        status.getEngineVersion(),
                        status.getProtocolVersion(),
                        Instant.ofEpochMilli(status.getStartedMs())),
                workers);
    }

    @Override
    public Optional<String> analysis(String runId) {
        return known(() -> call().analyze(request(runId)).getJson());
    }

    @Override
    public Optional<List<RunMetrics.Split>> splits(String runId) {
        return known(() -> call().listSplits(request(runId)).getSplitsList().stream()
                .map(split -> new RunMetrics.Split(
                        split.getNodeId(),
                        split.getSplitId(),
                        split.getSubtask(),
                        split.getState().name().replace("SPLIT_STATE_", "")))
                .toList());
    }

    @Override
    public RunMetrics.LogChunk readLog(String runId, RunMetrics.Task task, LogQuery query) {
        ReadTaskLogRequest.Builder request = ReadTaskLogRequest.newBuilder()
                .setHeader(header())
                .setRunId(runId)
                .setTask(ExecutionAttemptId.newBuilder()
                        .setRunId(runId)
                        .setVertexId(task.vertex())
                        .setSubtask(task.subtask())
                        .setAttempt(task.attempt()))
                .setOffset(query.offset())
                .setMaxBytes(query.maxBytes())
                .setContains(query.contains());
        if (query.minLevel() != null) {
            request.setMinLevel(query.minLevel());
        }
        try {
            Iterator<LogChunk> chunks = call().readTaskLog(request.build());
            StringBuilder text = new StringBuilder();
            long offset = query.offset();
            boolean eof = false;
            while (chunks.hasNext()) {
                LogChunk chunk = chunks.next();
                text.append(chunk.getData().toStringUtf8());
                offset = chunk.getOffset();
                eof = chunk.getEof();
            }
            return new RunMetrics.LogChunk(offset, text.toString(), eof);
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, address());
        }
    }

    private static JobRequest request(String runId) {
        return JobRequest.newBuilder().setHeader(header()).setRunId(runId).build();
    }

    static RunMetrics metrics(org.devlive.pipeforge.engine.rpc.proto.JobMetrics metrics) {
        return new RunMetrics(
                Instant.ofEpochMilli(metrics.getTimestampMs()),
                metrics.getElapsedMs(),
                metrics.getRowsRead(),
                metrics.getRowsWritten(),
                metrics.getRowsFiltered(),
                metrics.getRowsExpanded(),
                metrics.getRowsDirty(),
                metrics.getRowsFailed(),
                metrics.getRowsInFlight(),
                metrics.getReadRowsPerSecond(),
                metrics.getWriteRowsPerSecond(),
                metrics.getReaderWaitMs(),
                metrics.getWriterWaitMs(),
                metrics.getBackPressuredMs(),
                metrics.getThrottleWaitMs(),
                metrics.getExchangedBytes(),
                metrics.getMemoryBytes(),
                metrics.getQueueDepth(),
                metrics.getQueueCapacity(),
                metrics.getTasksRunning(),
                metrics.getParallelism(),
                metrics.getSplitsTotal(),
                metrics.getSplitsFinished(),
                metrics.getNodesList().stream()
                        .map(n -> new RunMetrics.NodeMetrics(
                                n.getNodeId(),
                                n.getRowsIn(),
                                n.getRowsOut(),
                                n.getRowsFiltered(),
                                n.getRowsExpanded(),
                                n.getRowsDirty(),
                                n.getBytesIn(),
                                n.getBytesOut()))
                        .toList(),
                metrics.getDirtySamplesList().stream()
                        .map(d -> new RunMetrics.DirtySample(
                                d.getNodeId(),
                                d.getTable().isEmpty() ? null : d.getTable(),
                                d.getCode(),
                                d.getMessage(),
                                d.getRow(),
                                Instant.ofEpochMilli(d.getAtMs())))
                        .toList(),
                metrics.getLastCompletedCheckpointId(),
                metrics.getLastCheckpointDurationMs(),
                metrics.getLastCheckpointSize(),
                metrics.getCheckpointFailures());
    }

    /** How long a savepoint may take before the server stops waiting for it. */
    static final Duration SAVEPOINT_TIMEOUT = Duration.ofMinutes(15);

    @Override
    public Savepoint savepoint(String runId, @Nullable String name) {
        try {
            var answer = stub.withDeadlineAfter(SAVEPOINT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .triggerSavepoint(savepointRequest(runId, name));
            return new Savepoint(answer.getCheckpointId(), answer.getPath());
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, address());
        }
    }

    @Override
    public Savepoint pause(String runId, @Nullable String name) {
        try {
            var answer = stub.withDeadlineAfter(SAVEPOINT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .stopWithSavepoint(savepointRequest(runId, name));
            return new Savepoint(answer.getCheckpointId(), answer.getPath());
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, address());
        }
    }

    private static org.devlive.pipeforge.engine.rpc.proto.SavepointRequest savepointRequest(
            String runId, @Nullable String name) {
        return org.devlive.pipeforge.engine.rpc.proto.SavepointRequest.newBuilder()
                .setHeader(Headers.create(Headers.Epochs.FIRST, SAVEPOINT_TIMEOUT, System::currentTimeMillis))
                .setRunId(runId)
                .setName(name == null ? "" : name)
                .build();
    }

    @Override
    public void drain(String workerId) {
        try {
            call().drainWorker(org.devlive.pipeforge.engine.rpc.proto.WorkerRequest.newBuilder()
                    .setHeader(header())
                    .setWorkerId(workerId)
                    .build());
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, address());
        }
    }

    @Override
    public void uncordon(String workerId) {
        try {
            call().uncordonWorker(org.devlive.pipeforge.engine.rpc.proto.WorkerRequest.newBuilder()
                    .setHeader(header())
                    .setWorkerId(workerId)
                    .build());
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, address());
        }
    }

    @Override
    public String failover() {
        String leader = address();
        try {
            call().releaseLeadership(org.devlive.pipeforge.engine.rpc.proto.ClusterRequest.newBuilder()
                    .setHeader(header())
                    .build());
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, leader);
        }
        return leader;
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (Exception e) {
            // closing the channels does not fail
        }
    }
}
