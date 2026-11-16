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
 * The gRPC side of the {@link Dispatcher}: the server submits, cancels, lists and watches runs
 * (protocol specification, section 2.1). Run analysis comes with the analysis of runs and answers
 * {@code UNIMPLEMENTED} until then.
 */
public final class DispatcherService extends DispatcherServiceGrpc.DispatcherServiceImplBase {

    /** Runs a page lists when the request does not say. */
    static final int DEFAULT_PAGE = 100;

    private final Dispatcher dispatcher;

    public DispatcherService(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
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
                    request.getTimeoutMs() == 0 ? null : Duration.ofMillis(request.getTimeoutMs())));
            return SubmitJobResponse.newBuilder().setRunId(status.runId()).build();
        });
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
                .setStartMs(run.started().toEpochMilli());
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
                .setParallelism(metrics.parallelism());
        for (NodeMetrics node : metrics.nodes()) {
            proto.addNodes(org.devlive.pipeforge.engine.rpc.proto.NodeMetrics.newBuilder()
                    .setNodeId(node.node())
                    .setParallelism(metrics.parallelism())
                    .setRowsIn(node.rowsIn())
                    .setRowsOut(node.rowsOut())
                    .setRowsFiltered(node.rowsFiltered())
                    .setRowsExpanded(node.rowsExpanded())
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
            case RUN_STATE_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalArgumentException(
                    "Unknown run state " + state);
        };
    }
}
