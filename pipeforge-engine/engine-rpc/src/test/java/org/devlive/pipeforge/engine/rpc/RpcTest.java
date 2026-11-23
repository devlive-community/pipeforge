/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.api.serialization.StringSerializer;
import org.devlive.pipeforge.api.source.ReaderEvent;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class RpcTest {

    private static final ExecutionAttemptId TASK = new ExecutionAttemptId("run", "src", 1, 0);

    @Test
    void attemptIdsPrintAsAPath() {
        assertThat(TASK).hasToString("run/src/1/0");
        assertThatThrownBy(() -> new ExecutionAttemptId("run", "src", -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serializedValuesRoundTripAndCopyTheirBytes() throws IOException {
        SerializedValue value = SerializedValue.of(StringSerializer.INSTANCE, "split-1");
        byte[] bytes = value.bytes();
        bytes[0] = 'X';

        assertThat(value.deserialize(StringSerializer.INSTANCE)).isEqualTo("split-1");
        assertThat(value.version()).isEqualTo(StringSerializer.INSTANCE.version());
        assertThat(value).isEqualTo(SerializedValue.of(StringSerializer.INSTANCE, "split-1"));
        assertThat(value.hashCode())
                .isEqualTo(
                        SerializedValue.of(StringSerializer.INSTANCE, "split-1").hashCode());
        assertThat(value).isNotEqualTo(new SerializedValue(99, value.bytes()));
        assertThat(value.toString()).contains("7 bytes");
    }

    @Test
    void taskStatesKnowWhichAreFinal() {
        assertThat(List.of(TaskState.values()).stream().filter(TaskState::isTerminal))
                .containsExactly(TaskState.FINISHED, TaskState.FAILED, TaskState.CANCELED);
    }

    @Test
    void stateUpdatesCarryAnErrorExactlyWhenTheTaskFailed() {
        PipeForgeException error = new PipeForgeException(ErrorCode.INTERNAL, Map.of("detail", "x"));

        assertThat(TaskStateUpdate.failed(TASK, error).error()).isSameAs(error);
        assertThat(TaskStateUpdate.of(TASK, TaskState.RUNNING).committables()).isEmpty();
        TaskMetricsSnapshot metrics =
                new TaskMetricsSnapshot(List.of(new OperatorSnapshot("src", 3, 3, 0, 0, 10)), 100, 5, 1, 2);
        assertThat(TaskStateUpdate.of(TASK, TaskState.CANCELED)
                        .withMetrics(metrics)
                        .metrics())
                .isSameAs(metrics);
        assertThat(TaskStateUpdate.of(TASK, TaskState.RUNNING).metrics()).isNull();
        assertThat(TaskMetricsSnapshot.EMPTY.operators()).isEmpty();
        assertThat(TaskStateUpdate.finished(TASK, List.of(new SerializedValue(1, new byte[] {1})))
                        .committables())
                .hasSize(1);
        assertThatThrownBy(() -> TaskStateUpdate.of(TASK, TaskState.FAILED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskStateUpdate(TASK, TaskState.FINISHED, error, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messagesRequireTheirParts() {
        assertThat(new OperatorEvent.AddSplits("src", List.of()).node()).isEqualTo("src");
        assertThat(new OperatorEvent.NoMoreSplits("src").node()).isEqualTo("src");
        ReaderEvent event = new ReaderEvent() {};
        assertThat(new ReaderMessage.Custom(TASK, "src", event).event()).isSameAs(event);
        assertThat(new ReaderMessage.SplitRequest(TASK, "src").task()).isEqualTo(TASK);
        // the split request and the finished splits travel over the protocol
        for (ReaderMessage message : List.<ReaderMessage>of(
                new ReaderMessage.SplitRequest(TASK, "src"),
                new ReaderMessage.SplitsFinished(TASK, "src", List.of("split-1", "split-2")))) {
            assertThat(ProtoMapping.fromProto(ProtoMapping.toProto(message).build()))
                    .isEqualTo(message);
        }
        assertThatThrownBy(() -> new ReaderMessage.Custom(TASK, "src", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TaskSettings(
                        true, 0, 1, java.time.Duration.ofSeconds(1), TaskSettings.DEFAULT_METRICS_INTERVAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TaskSettings.defaults().asyncWriteBuffer()).isTrue();
    }

    @Test
    void endpointsRunCallsInOrderOnTheirOwnThread() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        JobMasterGateway target = new JobMasterGateway() {
            @Override
            public CompletableFuture<Void> updateTaskState(TaskStateUpdate update) {
                calls.add(Thread.currentThread().getName() + " " + update.state());
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> sendReaderMessage(ReaderMessage message) {
                calls.add(Thread.currentThread().getName() + " request");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> reportMetrics(ExecutionAttemptId task, TaskMetricsSnapshot metrics) {
                calls.add(Thread.currentThread().getName() + " metrics");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> acknowledgeCheckpoint(CheckpointAck ack) {
                calls.add(Thread.currentThread().getName() + " ack " + ack.checkpointId());
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> declineCheckpoint(
                    ExecutionAttemptId task,
                    long checkpointId,
                    org.devlive.pipeforge.common.error.PipeForgeException reason) {
                calls.add(Thread.currentThread().getName() + " decline " + checkpointId);
                return CompletableFuture.completedFuture(null);
            }
        };
        try (InProcessRpc.Endpoint<JobMasterGateway> endpoint = InProcessRpc.serve("m", target)) {
            endpoint.gateway().updateTaskState(TaskStateUpdate.of(TASK, TaskState.RUNNING));
            endpoint.gateway().sendReaderMessage(new ReaderMessage.SplitRequest(TASK, "src"));
            endpoint.gateway().reportMetrics(TASK, TaskMetricsSnapshot.EMPTY);
            endpoint.gateway().acknowledgeCheckpoint(new CheckpointAck(TASK, 4, List.of(), CheckpointTaskMetrics.NONE));
            endpoint.gateway()
                    .declineCheckpoint(
                            TASK,
                            5,
                            org.devlive.pipeforge.common.error.PipeForgeException.of(
                                            org.devlive.pipeforge.common.error.ErrorCode.INTERNAL)
                                    .param("detail", "x")
                                    .build());
            endpoint.gateway()
                    .updateTaskState(TaskStateUpdate.of(TASK, TaskState.CANCELED))
                    .get(5, TimeUnit.SECONDS);
        }
        assertThat(calls)
                .containsExactly(
                        "rpc-m RUNNING",
                        "rpc-m request",
                        "rpc-m metrics",
                        "rpc-m ack 4",
                        "rpc-m decline 5",
                        "rpc-m CANCELED");
    }

    @Test
    void failuresOfTheCalleeAndClosedEndpointsReachTheCaller() {
        TaskExecutorGateway target = new TaskExecutorGateway() {
            @Override
            public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
                throw new IllegalStateException("no slot");
            }

            @Override
            public CompletableFuture<Void> cancelTask(ExecutionAttemptId task) {
                return CompletableFuture.failedFuture(new IllegalStateException("unknown"));
            }

            @Override
            public CompletableFuture<Void> sendOperatorEvent(ExecutionAttemptId task, OperatorEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> requestSlot(SlotRequest request) {
                return CompletableFuture.failedFuture(new IllegalStateException("slot " + request.slot() + " taken"));
            }

            @Override
            public CompletableFuture<Void> freeSlot(String allocationId) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> triggerCheckpoint(CheckpointTrigger trigger) {
                return CompletableFuture.failedFuture(new IllegalStateException("not a source"));
            }

            @Override
            public CompletableFuture<Void> notifyCheckpointComplete(ExecutionAttemptId task, long checkpointId) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> abortCheckpoint(ExecutionAttemptId task, long checkpointId, String reason) {
                return CompletableFuture.completedFuture(null);
            }
        };
        InProcessRpc.Endpoint<TaskExecutorGateway> endpoint = InProcessRpc.serve("w", target);
        TaskExecutorGateway gateway = endpoint.gateway();

        assertThatThrownBy(() -> gateway.deployTask(null).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("no slot");
        assertThatThrownBy(() -> gateway.cancelTask(TASK).get(5, TimeUnit.SECONDS))
                .hasRootCauseMessage("unknown");
        assertThat(gateway.sendOperatorEvent(TASK, new OperatorEvent.NoMoreSplits("src")))
                .succeedsWithin(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> gateway.triggerCheckpoint(
                                new CheckpointTrigger(TASK, 1, CheckpointType.CHECKPOINT, 0, false))
                        .get(5, TimeUnit.SECONDS))
                .hasRootCauseMessage("not a source");
        assertThat(gateway.notifyCheckpointComplete(TASK, 1)).succeedsWithin(5, TimeUnit.SECONDS);
        assertThat(gateway.abortCheckpoint(TASK, 2, "timeout")).succeedsWithin(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> gateway.requestSlot(new SlotRequest("job", "run", "a1", 2))
                        .get(5, TimeUnit.SECONDS))
                .hasRootCauseMessage("slot 2 taken");
        assertThat(gateway.freeSlot("a1")).succeedsWithin(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> new SlotRequest("job", "run", "a1", -1)).isInstanceOf(IllegalArgumentException.class);
        endpoint.close();
        assertThat(gateway.cancelTask(TASK)).isCompletedExceptionally();
    }
}
