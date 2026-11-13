/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import com.google.protobuf.ByteString;
import io.grpc.Channel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.rpc.ChainCodec;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.proto.CancelTaskRequest;
import org.devlive.pipeforge.engine.rpc.proto.SecretBundle;
import org.devlive.pipeforge.engine.rpc.proto.TaskExecutorServiceGrpc;

/**
 * A worker as the master calls it over gRPC: the same {@link TaskExecutorGateway} as in process.
 * Task descriptions are encoded with {@link ChainCodec}; sensitive options are sealed for the
 * worker's public key; operator events are numbered per task and node.
 */
public final class TaskExecutorClient implements TaskExecutorGateway {

    static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(60);
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private record Stream(ExecutionAttemptId task, String node) {}

    private final TaskExecutorServiceGrpc.TaskExecutorServiceFutureStub stub;
    private final String target;
    private final byte[] publicKey;
    private final Supplier<Headers.Epochs> epochs;
    private final Retry retry;
    private final Map<Stream, AtomicLong> sequences = new ConcurrentHashMap<>();

    /**
     * @param target how errors name the worker, e.g. its address
     * @param publicKey the X25519 key the worker registered with
     * @param epochs the epochs of the master that calls
     */
    public TaskExecutorClient(
            Channel channel, String target, byte[] publicKey, Supplier<Headers.Epochs> epochs, Retry retry) {
        this.stub = TaskExecutorServiceGrpc.newFutureStub(channel);
        this.target = target;
        this.publicKey = publicKey.clone();
        this.epochs = epochs;
        this.retry = retry;
    }

    @Override
    public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
        var request = org.devlive.pipeforge.engine.rpc.proto.TaskDeployment.newBuilder();
        try {
            ChainCodec.Encoded encoded = ChainCodec.encode(deployment.chain(), deployment.run());
            request.setHeader(Headers.create(epochs.get(), DEPLOY_TIMEOUT, System::currentTimeMillis))
                    .setId(ProtoMapping.toProto(deployment.id()))
                    .setChainDescriptor(ByteString.copyFrom(encoded.descriptor()))
                    .setParallelism(deployment.parallelism())
                    .setSettings(ProtoMapping.toProto(deployment.settings()));
            for (PlannedNode node : deployment.chain().nodes()) {
                request.addPlugins(ChainCodec.pluginRef(node.plugin()));
            }
            if (!encoded.secrets().isEmpty()) {
                byte[] bundle = SecretBundle.newBuilder()
                        .putAllValues(encoded.secrets())
                        .build()
                        .toByteArray();
                request.setEncryptedSecrets(ByteString.copyFrom(
                        SecretBox.seal(publicKey, deployment.id().toString(), bundle)));
            }
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(PipeForgeException.wrap(e));
        }
        var built = request.build();
        return call(() -> stub.withDeadlineAfter(DEPLOY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .deployTask(built));
    }

    @Override
    public CompletableFuture<Void> cancelTask(ExecutionAttemptId task) {
        var request = CancelTaskRequest.newBuilder()
                .setHeader(Headers.create(epochs.get(), TIMEOUT, System::currentTimeMillis))
                .setId(ProtoMapping.toProto(task))
                .build();
        return call(() -> stub.withDeadlineAfter(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .cancelTask(request));
    }

    @Override
    public CompletableFuture<Void> sendOperatorEvent(ExecutionAttemptId task, OperatorEvent event) {
        long sequence = sequences
                .computeIfAbsent(new Stream(task, event.node()), s -> new AtomicLong())
                .incrementAndGet();
        var request = ProtoMapping.toProto(event)
                .setHeader(Headers.create(epochs.get(), TIMEOUT, System::currentTimeMillis))
                .setTarget(ProtoMapping.toProto(task))
                .setSequence(sequence)
                .build();
        return call(() -> stub.withDeadlineAfter(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .sendOperatorEvent(request));
    }

    /** Forgets the event numbers of a task that ended. */
    public void forget(ExecutionAttemptId task) {
        sequences.keySet().removeIf(s -> s.task().equals(task));
    }

    @SuppressWarnings("FutureReturnValueIgnored") // the callback completes another future
    private CompletableFuture<Void> call(
            Supplier<com.google.common.util.concurrent.ListenableFuture<org.devlive.pipeforge.engine.rpc.proto.Ack>>
                    send) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        retry.call(() -> GrpcTransport.completable(send.get())).whenComplete((ack, error) -> {
            if (error != null) {
                result.completeExceptionally(RpcErrors.fromStatus(error, target));
            } else {
                result.complete(null);
            }
        });
        return result;
    }
}
