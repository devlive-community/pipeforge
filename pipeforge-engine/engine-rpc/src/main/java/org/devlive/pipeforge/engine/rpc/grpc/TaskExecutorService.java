/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.StreamObserver;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ChainCodec;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.SlotRequest;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.proto.Ack;
import org.devlive.pipeforge.engine.rpc.proto.CancelTaskRequest;
import org.devlive.pipeforge.engine.rpc.proto.Header;
import org.devlive.pipeforge.engine.rpc.proto.SecretBundle;
import org.devlive.pipeforge.engine.rpc.proto.TaskExecutorServiceGrpc;
import org.jspecify.annotations.Nullable;

/**
 * The gRPC side of a worker: checks the protocol version and the epochs of every request, runs each
 * request ID once, decrypts the sensitive options, rebuilds the task description with the worker's
 * plugins and hands operator events over in order, then calls the worker's {@link
 * TaskExecutorGateway}. Reading task logs answers {@code UNIMPLEMENTED} until the log service.
 */
public final class TaskExecutorService extends TaskExecutorServiceGrpc.TaskExecutorServiceImplBase {

    private record Stream(ExecutionAttemptId task, String node) {}

    private final TaskExecutorGateway target;
    private final SecretBox keys;
    private final Resolvers resolvers;
    private final Fencing fencing;
    private final IdempotencyCache requests;
    private final Sequencer<Stream, OperatorEvent> events = new Sequencer<>();

    /** Finds the components of a run among the plugins of the worker. */
    @FunctionalInterface
    public interface Resolvers {
        /**
         * The resolver of a run's components.
         *
         * @param plugins the plugins the deployment names, with the SHA-256 of their packages
         */
        ChainCodec.ComponentResolver forRun(
                String runId, java.util.List<org.devlive.pipeforge.engine.rpc.proto.PluginRef> plugins);
    }

    /**
     * @param keys the key pair whose public key the worker registered with
     * @param resolvers finds the components a task description names among the worker's plugins
     */
    public TaskExecutorService(
            TaskExecutorGateway target,
            SecretBox keys,
            Resolvers resolvers,
            Fencing fencing,
            IdempotencyCache requests) {
        this.target = target;
        this.keys = keys;
        this.resolvers = resolvers;
        this.fencing = fencing;
        this.requests = requests;
    }

    @Override
    public void deployTask(
            org.devlive.pipeforge.engine.rpc.proto.TaskDeployment request, StreamObserver<Ack> observer) {
        handle(observer, request.getHeader(), request.getId().getRunId(), () -> target.deployTask(deployment(request)));
    }

    private TaskDeployment deployment(org.devlive.pipeforge.engine.rpc.proto.TaskDeployment request) {
        ExecutionAttemptId id = ProtoMapping.fromProto(request.getId());
        Map<String, String> secrets = Map.of();
        if (!request.getEncryptedSecrets().isEmpty()) {
            try {
                byte[] bundle = keys.open(request.getEncryptedSecrets().toByteArray(), id.toString());
                secrets = SecretBundle.parseFrom(bundle).getValuesMap();
            } catch (GeneralSecurityException | InvalidProtocolBufferException e) {
                throw PipeForgeException.of(ErrorCode.SECRETS_UNREADABLE)
                        .param("task", id)
                        .param("reason", e.getMessage())
                        .build();
            }
        }
        ChainCodec.Decoded decoded = ChainCodec.decode(
                request.getChainDescriptor().toByteArray(),
                secrets,
                resolvers.forRun(id.runId(), request.getPluginsList()));
        return new TaskDeployment(
                id,
                decoded.chain(),
                request.getParallelism(),
                ProtoMapping.fromProto(request.getSettings()),
                decoded.run(),
                request.getAllocationId().isEmpty() ? null : request.getAllocationId());
    }

    @Override
    public void cancelTask(CancelTaskRequest request, StreamObserver<Ack> observer) {
        ExecutionAttemptId id = ProtoMapping.fromProto(request.getId());
        handle(observer, request.getHeader(), id.runId(), () -> {
            events.forget(stream -> stream.task().equals(id));
            return target.cancelTask(id);
        });
    }

    @Override
    public void sendOperatorEvent(
            org.devlive.pipeforge.engine.rpc.proto.OperatorEvent request, StreamObserver<Ack> observer) {
        ExecutionAttemptId id = ProtoMapping.fromProto(request.getTarget());
        handle(observer, request.getHeader(), id.runId(), () -> {
            OperatorEvent event = ProtoMapping.fromProto(request);
            List<CompletableFuture<Void>> delivered = new ArrayList<>();
            events.accept(
                    new Stream(id, request.getNodeId()),
                    request.getSequence(),
                    event,
                    e -> delivered.add(target.sendOperatorEvent(id, e)));
            return CompletableFuture.allOf(delivered.toArray(CompletableFuture[]::new));
        });
    }

    @Override
    public void requestSlot(org.devlive.pipeforge.engine.rpc.proto.SlotRequest request, StreamObserver<Ack> observer) {
        handle(
                observer,
                request.getHeader(),
                request.getRunId(),
                () -> target.requestSlot(new SlotRequest(
                        request.getJobId(), request.getRunId(), request.getAllocationId(), request.getSlotIndex())));
    }

    @Override
    public void freeSlot(org.devlive.pipeforge.engine.rpc.proto.FreeSlotRequest request, StreamObserver<Ack> observer) {
        // a release carries no run: it is checked against the leader epoch only
        handle(observer, request.getHeader(), null, () -> target.freeSlot(request.getAllocationId()));
    }

    private void handle(
            StreamObserver<Ack> observer, Header header, @Nullable String run, Supplier<CompletableFuture<?>> work) {
        CompletableFuture<?> result;
        try {
            Headers.checkVersion(header);
            if (run == null) {
                fencing.checkLeader(header);
            } else {
                fencing.check(header, run);
            }
            result = requests.run(header.getRequestId(), () -> {
                try {
                    return work.get().thenApply(ignored -> null);
                } catch (RuntimeException e) {
                    return CompletableFuture.failedFuture(e);
                }
            });
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        GrpcTransport.respond(observer, result);
    }
}
