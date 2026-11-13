/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ChainCodecTest;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.OperatorSnapshot;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.ReaderMessage;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskSettings;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.rpc.proto.TaskExecutorServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The master and a worker talking over gRPC (in process): deployment with sealed secrets, the
 * cluster token, fencing, protocol versions, idempotent retries and ordered events.
 */
class GrpcTransportTest {

    static final ExecutionAttemptId TASK = new ExecutionAttemptId("run-7", "src", 0, 0);
    static final ClusterToken TOKEN = new ClusterToken("cluster-secret");

    /** A worker that records what it is asked to do. */
    static final class Worker implements TaskExecutorGateway {
        final List<TaskDeployment> deployed = new CopyOnWriteArrayList<>();
        final List<ExecutionAttemptId> cancelled = new CopyOnWriteArrayList<>();
        final List<OperatorEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
            deployed.add(deployment);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> cancelTask(ExecutionAttemptId task) {
            cancelled.add(task);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendOperatorEvent(ExecutionAttemptId task, OperatorEvent event) {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** A job master that records what tasks report. */
    static final class Master implements JobMasterGateway {
        final List<TaskStateUpdate> updates = new CopyOnWriteArrayList<>();
        final List<ReaderMessage> messages = new CopyOnWriteArrayList<>();
        final List<TaskMetricsSnapshot> metrics = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Void> updateTaskState(TaskStateUpdate update) {
            updates.add(update);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendReaderMessage(ReaderMessage message) {
            messages.add(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> reportMetrics(ExecutionAttemptId task, TaskMetricsSnapshot snapshot) {
            metrics.add(snapshot);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Answers the first {@code failures} calls with UNAVAILABLE, as an unreachable process would. */
    static final class Flaky implements ServerInterceptor {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(
                ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            calls.incrementAndGet();
            if (failures.getAndDecrement() > 0) {
                call.close(Status.UNAVAILABLE.withDescription("connection refused"), new Metadata());
                return new ServerCall.Listener<>() {};
            }
            return next.startCall(call, headers);
        }
    }

    final String name = "pipeforge-" + UUID.randomUUID();
    final Worker worker = new Worker();
    final Master master = new Master();
    final SecretBox keys = SecretBox.generate();
    final Fencing fencing = new Fencing();
    final Flaky flaky = new Flaky();
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    final Retry retry = new Retry(scheduler, Duration.ofMillis(5));
    Server server;
    ManagedChannel channel;

    @BeforeEach
    void start() throws Exception {
        server = InProcessServerBuilder.forName(name)
                .intercept(TOKEN.server())
                .addService(ServerInterceptors.intercept(
                        new TaskExecutorService(
                                worker, keys, ChainCodecTest.Fake::new, fencing, new IdempotencyCache()),
                        flaky))
                .addService(new JobMasterService(master, new IdempotencyCache()))
                .build()
                .start();
        channel = channel(TOKEN);
    }

    ManagedChannel channel(ClusterToken token) {
        return InProcessChannelBuilder.forName(name).intercept(token.client()).build();
    }

    @AfterEach
    void stop() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        scheduler.shutdownNow();
    }

    TaskExecutorClient executor(Headers.Epochs epochs) {
        return new TaskExecutorClient(channel, "worker-1", keys.publicKey(), () -> epochs, retry);
    }

    static TaskDeployment deployment() {
        return new TaskDeployment(
                TASK,
                ChainCodecTest.sample(),
                2,
                new TaskSettings(true, 256, 2, Duration.ofSeconds(20), Duration.ofMillis(500)),
                ChainCodecTest.RUN);
    }

    static PipeForgeException failure(CompletableFuture<?> future) {
        Throwable error = future.handle((v, e) -> e).join();
        assertThat(error).isInstanceOf(PipeForgeException.class);
        return (PipeForgeException) error;
    }

    @Test
    void deploysATaskWithItsSensitiveOptions() {
        executor(Headers.Epochs.FIRST).deployTask(deployment()).join();

        assertThat(worker.deployed).hasSize(1);
        TaskDeployment received = worker.deployed.get(0);
        assertThat(received.id()).isEqualTo(TASK);
        assertThat(received.parallelism()).isEqualTo(2);
        assertThat(received.settings()).isEqualTo(deployment().settings());
        assertThat(received.run()).isEqualTo(ChainCodecTest.RUN);
        assertThat(received.chain().nodes()).extracting(n -> n.id()).containsExactly("src", "tidy", "out");
        assertThat(received.chain().source().config().raw("password")).isEqualTo("s3cr3t!");
    }

    @Test
    void secretsSealedForAnotherKeyCannotBeRead() {
        TaskExecutorClient stale = new TaskExecutorClient(
                channel, "worker-1", SecretBox.generate().publicKey(), () -> Headers.Epochs.FIRST, retry);
        PipeForgeException error = failure(stale.deployTask(deployment()));
        assertThat(error.code()).isEqualTo(ErrorCode.SECRETS_UNREADABLE);
        assertThat(error.params()).containsEntry("task", TASK.toString());
        assertThat(worker.deployed).isEmpty();
    }

    @Test
    void callsWithoutTheClusterTokenAreRefused() {
        ManagedChannel wrong = channel(new ClusterToken("not-the-token"));
        try {
            TaskExecutorClient client =
                    new TaskExecutorClient(wrong, "worker-1", keys.publicKey(), () -> Headers.Epochs.FIRST, retry);
            PipeForgeException error = failure(client.cancelTask(TASK));
            assertThat(error.code()).isEqualTo(ErrorCode.RPC_UNAUTHENTICATED);
            assertThat(worker.cancelled).isEmpty();
        } finally {
            wrong.shutdownNow();
        }
    }

    @Test
    void requestsOfAReplacedMasterAreRefused() {
        executor(new Headers.Epochs(2, 1)).cancelTask(TASK).join();
        PipeForgeException error = failure(executor(new Headers.Epochs(1, 1)).cancelTask(TASK));
        assertThat(error.code()).isEqualTo(ErrorCode.RPC_STALE_EPOCH);
        assertThat(error.params()).containsEntry("received", "1").containsEntry("known", "2");
        assertThat(worker.cancelled).hasSize(1);
    }

    @Test
    void anotherMajorVersionIsRefused() {
        var stub = TaskExecutorServiceGrpc.newBlockingStub(channel);
        var request = org.devlive.pipeforge.engine.rpc.proto.CancelTaskRequest.newBuilder()
                .setHeader(RpcPartsTest.header(1, 1).toBuilder().setProtocolVersion("2.0"))
                .setId(ProtoMapping.toProto(TASK))
                .build();
        assertThatThrownBy(() -> stub.cancelTask(request))
                .satisfies(e -> assertThat(RpcErrors.fromStatus(e, "worker-1").code())
                        .isEqualTo(ErrorCode.RPC_VERSION_MISMATCH))
                .hasMessageStartingWith("UNIMPLEMENTED");
    }

    @Test
    void aRequestThatArrivesTwiceRunsOnce() {
        var stub = TaskExecutorServiceGrpc.newBlockingStub(channel);
        var request = org.devlive.pipeforge.engine.rpc.proto.CancelTaskRequest.newBuilder()
                .setHeader(RpcPartsTest.header(1, 1))
                .setId(ProtoMapping.toProto(TASK))
                .build();
        stub.cancelTask(request);
        stub.cancelTask(request);
        assertThat(worker.cancelled).hasSize(1);
    }

    @Test
    void unansweredRequestsAreRetriedThreeTimes() {
        flaky.failures.set(3);
        executor(Headers.Epochs.FIRST).cancelTask(TASK).join();
        assertThat(flaky.calls).hasValue(4);
        assertThat(worker.cancelled).hasSize(1);

        flaky.failures.set(4);
        flaky.calls.set(0);
        PipeForgeException error = failure(executor(Headers.Epochs.FIRST).cancelTask(TASK));
        assertThat(flaky.calls).hasValue(4);
        assertThat(error.code()).isEqualTo(ErrorCode.RPC_UNAVAILABLE);
        assertThat(error.params()).containsEntry("target", "worker-1");
        assertThat(String.valueOf(error.params().get("reason"))).contains("UNAVAILABLE: connection refused");
    }

    @Test
    void refusalsAreNotRetried() {
        executor(new Headers.Epochs(5, 1)).cancelTask(TASK).join();
        flaky.calls.set(0);
        failure(executor(Headers.Epochs.FIRST).cancelTask(TASK));
        assertThat(flaky.calls).hasValue(1);
    }

    @Test
    void operatorEventsArriveInOrder() {
        var stub = TaskExecutorServiceGrpc.newBlockingStub(channel);
        SerializedValue split = new SerializedValue(1, new byte[] {7});
        for (long sequence : new long[] {2, 1, 1, 3}) {
            var event = sequence == 3
                    ? new OperatorEvent.NoMoreSplits("src")
                    : (OperatorEvent) new OperatorEvent.AddSplits("src", List.of(split));
            stub.sendOperatorEvent(ProtoMapping.toProto(event)
                    .setHeader(RpcPartsTest.header(1, 1))
                    .setTarget(ProtoMapping.toProto(TASK))
                    .setSequence(sequence)
                    .build());
        }
        assertThat(worker.events)
                .containsExactly(
                        new OperatorEvent.AddSplits("src", List.of(split)),
                        new OperatorEvent.AddSplits("src", List.of(split)),
                        new OperatorEvent.NoMoreSplits("src"));

        // the client numbers its events itself
        TaskExecutorClient client = executor(Headers.Epochs.FIRST);
        ExecutionAttemptId other = new ExecutionAttemptId("run-7", "src", 1, 0);
        client.sendOperatorEvent(other, new OperatorEvent.AddSplits("src", List.of(split)))
                .join();
        client.sendOperatorEvent(other, new OperatorEvent.NoMoreSplits("src")).join();
        assertThat(worker.events).hasSize(5).endsWith(new OperatorEvent.NoMoreSplits("src"));
    }

    @Test
    void tasksReportToTheJobMaster() throws Exception {
        try (JobMasterClient client = new JobMasterClient(channel, "master", retry)) {
            PipeForgeException failed = PipeForgeException.of(ErrorCode.INTERNAL)
                    .param("detail", "boom")
                    .scope(ErrorScope.node("src").withSubtask(0))
                    .build();
            client.updateTaskState(TaskStateUpdate.of(TASK, TaskState.RUNNING)).join();
            client.updateTaskState(TaskStateUpdate.failed(TASK, failed)).join();
            client.sendReaderMessage(new ReaderMessage.SplitRequest(TASK, "src"))
                    .join();

            TaskMetricsSnapshot snapshot =
                    new TaskMetricsSnapshot(List.of(new OperatorSnapshot("src", 0, 10, 0, 0, 5)), 100, 0, 0, 0);
            client.reportMetrics(TASK, snapshot).join();
            client.reportMetrics(TASK, snapshot).join();

            assertThat(master.updates)
                    .extracting(TaskStateUpdate::state)
                    .containsExactly(TaskState.RUNNING, TaskState.FAILED);
            PipeForgeException received = master.updates.get(1).error();
            assertThat(received).isNotNull();
            assertThat(received.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(received.scope()).isEqualTo(failed.scope());
            assertThat(master.messages).containsExactly(new ReaderMessage.SplitRequest(TASK, "src"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (master.metrics.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(master.metrics).containsExactly(snapshot, snapshot);

            PipeForgeException custom = failure(client.sendReaderMessage(
                    new ReaderMessage.Custom(TASK, "src", new org.devlive.pipeforge.api.source.ReaderEvent() {})));
            assertThat(custom.getMessage()).contains("Custom reader events of node 'src'");
        }
    }

    @Test
    void theRunStartTimeArrivesUnchanged() {
        executor(Headers.Epochs.FIRST).deployTask(deployment()).join();
        assertThat(worker.deployed.get(0).runStartTime()).isEqualTo(Instant.parse("2026-11-13T08:00:00Z"));
    }
}
