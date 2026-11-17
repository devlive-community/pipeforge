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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.RpcErrors;
import org.devlive.pipeforge.engine.rpc.proto.DispatcherServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.JobRequest;
import org.devlive.pipeforge.engine.rpc.proto.JobStatus;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.engine.rpc.proto.SubmitJobRequest;

/** The master's dispatcher over gRPC (protocol: DispatcherService). */
public final class GrpcMasterClient implements MasterClient {

    /** How long a call may take. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ManagedChannel channel;
    private final String address;
    private final DispatcherServiceGrpc.DispatcherServiceBlockingStub stub;

    public GrpcMasterClient(ManagedChannel channel, String address) {
        this.channel = channel;
        this.address = address;
        this.stub = DispatcherServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public String address() {
        return address;
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
                .setScheduledTimeMs(submission.scheduledTime().toEpochMilli());
        submission
                .plugins()
                .forEach((id, version) ->
                        request.addPlugins(PluginRef.newBuilder().setId(id).setVersion(version)));
        try {
            call().submitJob(request.build());
        } catch (RuntimeException e) {
            throw RpcErrors.fromStatus(e, address);
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
            throw RpcErrors.fromStatus(e, address);
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
            throw RpcErrors.fromStatus(e, address);
        } catch (RuntimeException e) {
            PipeForgeException error = RpcErrors.fromStatus(e, address);
            if (error.code().equals(ErrorCode.RUN_NOT_FOUND)) {
                return Optional.empty();
            }
            throw error;
        }
        Runs.RunError error = status.hasError()
                ? new Runs.RunError(
                        status.getError().getCode(), status.getError().getMessage())
                : null;
        return Optional.of(new Status(
                RunState.fromProto(status.getState()),
                status.getStartMs() == 0 ? null : Instant.ofEpochMilli(status.getStartMs()),
                error));
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}
