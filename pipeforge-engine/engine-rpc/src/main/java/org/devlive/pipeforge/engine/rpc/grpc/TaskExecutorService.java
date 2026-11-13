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
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.proto.Ack;
import org.devlive.pipeforge.engine.rpc.proto.CancelTaskRequest;
import org.devlive.pipeforge.engine.rpc.proto.Header;
import org.devlive.pipeforge.engine.rpc.proto.SecretBundle;
import org.devlive.pipeforge.engine.rpc.proto.TaskExecutorServiceGrpc;

/**
 * The gRPC side of a worker: checks the protocol version and the epochs of every request, runs each
 * request ID once, decrypts the sensitive options, rebuilds the task description with the worker's
 * plugins and hands operator events over in order, then calls the worker's {@link
 * TaskExecutorGateway}. RPCs of later steps (slots, logs) answer {@code UNIMPLEMENTED}.
 */
public final class TaskExecutorService extends TaskExecutorServiceGrpc.TaskExecutorServiceImplBase {

    private record Stream(ExecutionAttemptId task, String node) {}

    private final TaskExecutorGateway target;
    private final SecretBox keys;
    private final ChainCodec.ComponentResolver resolver;
    private final Fencing fencing;
    private final IdempotencyCache requests;
    private final Sequencer<Stream, OperatorEvent> events = new Sequencer<>();

    /**
     * @param keys the key pair whose public key the worker registered with
     * @param resolver finds the components a task description names among the worker's plugins
     */
    public TaskExecutorService(
            TaskExecutorGateway target,
            SecretBox keys,
            ChainCodec.ComponentResolver resolver,
            Fencing fencing,
            IdempotencyCache requests) {
        this.target = target;
        this.keys = keys;
        this.resolver = resolver;
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
        ChainCodec.Decoded decoded =
                ChainCodec.decode(request.getChainDescriptor().toByteArray(), secrets, resolver);
        return new TaskDeployment(
                id,
                decoded.chain(),
                request.getParallelism(),
                ProtoMapping.fromProto(request.getSettings()),
                decoded.run());
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

    private void handle(StreamObserver<Ack> observer, Header header, String run, Supplier<CompletableFuture<?>> work) {
        CompletableFuture<?> result;
        try {
            Headers.checkVersion(header);
            fencing.check(header, run);
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
