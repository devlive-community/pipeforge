/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.CheckpointSettings;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorages;
import org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage;
import org.devlive.pipeforge.engine.rpc.CheckpointAck;
import org.devlive.pipeforge.engine.rpc.CheckpointFiles;
import org.devlive.pipeforge.engine.rpc.CheckpointTaskMetrics;
import org.devlive.pipeforge.engine.rpc.CheckpointTrigger;
import org.devlive.pipeforge.engine.rpc.CheckpointType;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.SlotRequest;
import org.devlive.pipeforge.engine.rpc.StateHandle;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.CheckpointMetadata;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.OperatorState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The checkpoints of one run, on a simulated clock with three tasks: two readers and a writer. */
class CheckpointCoordinatorTest {

    static final String RUN = "run-1";
    static final ExecutionAttemptId SRC0 = new ExecutionAttemptId(RUN, "src", 0, 0);
    static final ExecutionAttemptId SRC1 = new ExecutionAttemptId(RUN, "src", 1, 0);
    static final ExecutionAttemptId SINK = new ExecutionAttemptId(RUN, "sink", 0, 0);
    static final List<ExecutionAttemptId> ALL = List.of(SRC0, SRC1, SINK);

    @TempDir
    Path dir;

    /** A clock and timer that move only when told. */
    static final class ManualTimer implements CheckpointCoordinator.Timer {
        record Entry(long due, long order, Runnable action, boolean[] cancelled) {}

        long now = 1_000_000;
        long order;
        final List<Entry> entries = new ArrayList<>();

        @Override
        public Runnable schedule(Runnable action, long delayMs) {
            boolean[] cancelled = {false};
            entries.add(new Entry(now + delayMs, order++, action, cancelled));
            return () -> cancelled[0] = true;
        }

        void advance(long ms) {
            long until = now + ms;
            while (true) {
                Entry next = entries.stream()
                        .filter(e -> e.due() <= until)
                        .min(Comparator.comparingLong(Entry::due).thenComparingLong(Entry::order))
                        .orElse(null);
                if (next == null) {
                    break;
                }
                entries.remove(next);
                now = Math.max(now, next.due());
                if (!next.cancelled()[0]) {
                    next.action().run();
                }
            }
            now = until;
        }
    }

    /** The workers of the tasks: records what they are told, and the store's view when told. */
    final class Workers implements TaskExecutorGateway {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<CheckpointTrigger> triggers = new CopyOnWriteArrayList<>();
        volatile @Nullable RuntimeException triggerFails;

        @Override
        public CompletableFuture<Void> triggerCheckpoint(CheckpointTrigger trigger) {
            triggers.add(trigger);
            calls.add("trigger " + trigger.task().vertexId() + "-"
                    + trigger.task().subtask() + " " + trigger.checkpointId());
            return triggerFails == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(triggerFails);
        }

        @Override
        public CompletableFuture<Void> notifyCheckpointComplete(ExecutionAttemptId task, long checkpointId) {
            calls.add("complete " + task.vertexId() + "-" + task.subtask() + " " + checkpointId + " recorded as "
                    + store.get(RUN, checkpointId).status());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> abortCheckpoint(ExecutionAttemptId task, long checkpointId, String reason) {
            calls.add("abort " + task.vertexId() + "-" + task.subtask() + " " + checkpointId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> cancelTask(ExecutionAttemptId task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> sendOperatorEvent(ExecutionAttemptId task, OperatorEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> requestSlot(SlotRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> freeSlot(String allocationId) {
            throw new UnsupportedOperationException();
        }

        List<String> of(String kind) {
            return calls.stream().filter(c -> c.startsWith(kind)).toList();
        }
    }

    final ManualTimer timer = new ManualTimer();
    final MemoryCheckpointStore store = new MemoryCheckpointStore();
    final Workers workers = new Workers();
    final List<PipeForgeException> runFailures = new ArrayList<>();
    byte[] enumeratorState = "splits a, b".getBytes(StandardCharsets.UTF_8);
    boolean running = true;
    CheckpointStorage storage;

    CheckpointCoordinator coordinator(CheckpointSettings settings) {
        return coordinator(settings, 0, CheckpointFiles.DEFAULT_INLINE_THRESHOLD);
    }

    CheckpointCoordinator coordinator(CheckpointSettings settings, long restored, int inlineThreshold) {
        if (storage == null) {
            storage = new FileSystemCheckpointStorage(dir);
        }
        CheckpointCoordinator.Tasks tasks = new CheckpointCoordinator.Tasks() {
            @Override
            public List<ExecutionAttemptId> sources() {
                return List.of(SRC0, SRC1);
            }

            @Override
            public List<ExecutionAttemptId> all() {
                return ALL;
            }

            @Override
            public boolean running() {
                return running;
            }

            @Override
            public TaskExecutorGateway gateway(ExecutionAttemptId task) {
                return workers;
            }
        };
        var context = new CheckpointCoordinator.Context(
                storage,
                store,
                tasks,
                id -> Map.of("src", new SerializedValue(3, enumeratorState)),
                timer,
                Runnable::run,
                Runnable::run,
                () -> timer.now,
                runFailures::add);
        var run = new CheckpointCoordinator.Run(
                RUN,
                "orders",
                1,
                Map.of("day", "2026-11-23"),
                "0.1.0",
                List.of(
                        new CheckpointCoordinator.Node("src", "source", "fake@1.0", 2),
                        new CheckpointCoordinator.Node("sink", "sink", "console@1.0", 1)));
        return new CheckpointCoordinator(settings, run, context, restored, inlineThreshold);
    }

    static CheckpointSettings settings(int tolerable, int retained) {
        return new CheckpointSettings(
                true,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ZERO,
                1,
                tolerable,
                retained,
                CheckpointSettings.Externalized.RETAIN_ON_CANCELLATION);
    }

    static CheckpointAck ack(ExecutionAttemptId task, long id) {
        StateHandle state = new StateHandle(
                task.vertexId(),
                task.vertexId().equals("src") ? "source" : "sink",
                1,
                new StateHandle.Inline(("state of " + task).getBytes(StandardCharsets.UTF_8)),
                100,
                7);
        return new CheckpointAck(task, id, List.of(state), new CheckpointTaskMetrics(1, 5, 2, 100));
    }

    void ackAll(CheckpointCoordinator coordinator, long id) {
        ALL.forEach(task -> coordinator.acknowledge(ack(task, id)));
    }

    @Test
    void triggersOnTheIntervalAndCompletesOnceEveryTaskAcknowledged() throws Exception {
        CheckpointCoordinator coordinator = coordinator(settings(3, 3));
        coordinator.start();
        timer.advance(9_999);
        assertThat(workers.triggers).isEmpty();
        timer.advance(1);
        assertThat(workers.of("trigger")).containsExactly("trigger src-0 1", "trigger src-1 1");
        assertThat(store.get(RUN, 1).status()).isEqualTo(CheckpointStore.Status.IN_PROGRESS);
        assertThat(store.get(RUN, 1).directory()).isEqualTo("run-1/chk-1/");

        coordinator.acknowledge(ack(SRC0, 1));
        coordinator.acknowledge(ack(SRC0, 1));
        coordinator.acknowledge(ack(SINK, 1));
        assertThat(storage.isComplete("run-1/chk-1/"))
                .as("one task still missing")
                .isFalse();
        timer.advance(250);
        coordinator.acknowledge(ack(SRC1, 1));

        assertThat(storage.isComplete("run-1/chk-1/")).isTrue();
        assertThat(workers.of("complete"))
                .as("the tasks hear of it only once it is recorded")
                .containsExactly(
                        "complete src-0 1 recorded as COMPLETED",
                        "complete src-1 1 recorded as COMPLETED",
                        "complete sink-0 1 recorded as COMPLETED");
        CheckpointStore.Checkpoint row = store.get(RUN, 1);
        assertThat(row.status()).isEqualTo(CheckpointStore.Status.COMPLETED);
        assertThat(row.tasksJson()).contains("\"task\":\"" + SRC0 + "\"", "\"asyncMs\":5");
        assertThat(store.lastOfAttempt).containsEntry(RUN + "#1", 1L);

        CheckpointMetadata metadata =
                CheckpointFiles.readMetadata(storage.read("run-1/chk-1/" + CheckpointStorages.METADATA), "1");
        assertThat(metadata.getCheckpointId()).isEqualTo(1);
        assertThat(metadata.getJobName()).isEqualTo("orders");
        assertThat(metadata.getVariablesMap()).containsEntry("day", "2026-11-23");
        assertThat(metadata.getOperatorsList())
                .extracting(OperatorState::getOperatorKind, OperatorState::getNodeId, o -> o.getSubtasksCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("enumerator", "src", 1),
                        org.assertj.core.groups.Tuple.tuple("source", "src", 2),
                        org.assertj.core.groups.Tuple.tuple("sink", "sink", 1));
        assertThat(metadata.getOperators(0).getSubtasks(0).getInline().toStringUtf8())
                .isEqualTo("splits a, b");
        assertThat(metadata.getOperators(0).getPlugin()).isEqualTo("fake@1.0");

        assertThat(coordinator.stats().lastCompletedId()).isEqualTo(1);
        assertThat(coordinator.stats().lastDurationMs()).isEqualTo(250);
        assertThat(coordinator.stats().lastSize()).isGreaterThan(300);
        timer.advance(10_000);
        assertThat(workers.of("trigger")).hasSize(4).endsWith("trigger src-1 2");
    }

    @Test
    void aLargeEnumeratorStateGoesToAFileOfItsOwn() throws Exception {
        enumeratorState = new byte[100];
        CheckpointCoordinator coordinator = coordinator(settings(3, 3), 0, 64);
        coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ackAll(coordinator, 1);
        CheckpointMetadata metadata = CheckpointFiles.readMetadata(storage.read("run-1/chk-1/_metadata"), "1");
        assertThat(metadata.getOperators(0).getSubtasks(0).getFile()).isEqualTo("enum-src.state");
        assertThat(CheckpointFiles.readState(storage.read("run-1/chk-1/enum-src.state"), 1, "src", "enum-src.state"))
                .hasSize(100);
    }

    @Test
    void aCheckpointThatTimesOutFailsAndIsDeleted() throws Exception {
        CheckpointCoordinator coordinator = coordinator(settings(3, 3));
        coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        coordinator.acknowledge(ack(SRC0, 1));
        storage.write("run-1/chk-1/src-0.state", new byte[] {1});
        timer.advance(60_000);

        CheckpointStore.Checkpoint row = store.get(RUN, 1);
        assertThat(row.status()).isEqualTo(CheckpointStore.Status.FAILED);
        assertThat(row.errorCode()).isEqualTo("PF-CKPT-0001");
        assertThat(row.errorMessage())
                .contains(SRC1.toString(), SINK.toString())
                .doesNotContain(SRC0.toString());
        assertThat(workers.of("abort")).containsExactly("abort src-0 1", "abort src-1 1", "abort sink-0 1");
        assertThat(storage.list("run-1/"))
                .as("what was written of it is deleted")
                .isEmpty();
        assertThat(coordinator.stats().failures()).isEqualTo(1);
        coordinator.acknowledge(ack(SRC1, 1));
        assertThat(storage.isComplete("run-1/chk-1/"))
                .as("a late ack changes nothing")
                .isFalse();
    }

    @Test
    void moreFailuresInARowThanTheJobToleratesFailTheRun() {
        CheckpointCoordinator coordinator = coordinator(settings(2, 3));
        for (long id = 1; id <= 2; id++) {
            coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
            coordinator.decline(
                    SINK,
                    id,
                    PipeForgeException.of(ErrorCode.INTERNAL)
                            .param("detail", "not ready")
                            .build());
        }
        assertThat(runFailures).isEmpty();
        // a completed checkpoint starts the count again
        coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ackAll(coordinator, 3);
        assertThat(coordinator.stats().failures()).isZero();
        for (long id = 4; id <= 6; id++) {
            coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
            coordinator.decline(
                    SINK,
                    id,
                    PipeForgeException.of(ErrorCode.INTERNAL)
                            .param("detail", "not ready")
                            .build());
        }
        assertThat(runFailures).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.TOO_MANY_CHECKPOINT_FAILURES);
            assertThat(e.params()).containsEntry("failures", 3).containsEntry("limit", 2);
            assertThat(e.getMessage()).contains("Task " + SINK + " declined checkpoint 6");
        });
        assertThat(store.get(RUN, 6).errorCode()).isEqualTo("PF-CKPT-0005");
    }

    @Test
    void aDeclineFromAWorkerKeepsItsReasonAndAFailedTriggerDeclinesToo() {
        CheckpointCoordinator coordinator = coordinator(settings(3, 3));
        coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        PipeForgeException fromWorker = PipeForgeException.of(ErrorCode.CHECKPOINT_DECLINED)
                .param("checkpoint", 1)
                .param("task", SRC0.toString())
                .param("reason", "worker w1 does not take snapshots of tasks")
                .build();
        coordinator.decline(SRC0, 1, fromWorker);
        assertThat(store.get(RUN, 1).errorMessage()).isEqualTo(fromWorker.getMessage());

        workers.triggerFails = new IllegalStateException("connection refused");
        coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        assertThat(store.get(RUN, 2).errorMessage()).contains("declined checkpoint 2", "connection refused");
    }

    @Test
    void keepsOnlyTheRetainedCheckpoints() throws Exception {
        CheckpointCoordinator coordinator = coordinator(settings(3, 2));
        for (long id = 1; id <= 4; id++) {
            coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
            ackAll(coordinator, id);
        }
        assertThat(coordinator.retained())
                .extracting(CheckpointCoordinator.Completed::checkpointId)
                .containsExactly(3L, 4L);
        assertThat(storage.isComplete("run-1/chk-1/")).isFalse();
        assertThat(storage.isComplete("run-1/chk-2/")).isFalse();
        assertThat(storage.isComplete("run-1/chk-3/")).isTrue();
        assertThat(storage.isComplete("run-1/chk-4/")).isTrue();
        assertThat(store.list(RUN))
                .extracting(CheckpointStore.Checkpoint::checkpointId)
                .containsExactly(4L, 3L);
    }

    @Test
    void aSavepointIsNeverCleanedUpAndItsFailureIsNotCounted() throws Exception {
        CheckpointCoordinator coordinator = coordinator(settings(0, 1));
        var savepoint = coordinator.trigger(CheckpointType.SAVEPOINT, "SAVEPOINT", "before-upgrade");
        ackAll(coordinator, 1);
        assertThat(savepoint)
                .isCompletedWithValueMatching(c -> c.directory().equals("run-1/savepoints/before-upgrade-1000000/"));
        coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ackAll(coordinator, 2);
        coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ackAll(coordinator, 3);
        assertThat(storage.isComplete("run-1/savepoints/before-upgrade-1000000/"))
                .isTrue();
        assertThat(storage.isComplete("run-1/chk-2/")).isFalse();

        var failed = coordinator.trigger(CheckpointType.SAVEPOINT, "SAVEPOINT", "again");
        coordinator.decline(
                SINK,
                4,
                PipeForgeException.of(ErrorCode.INTERNAL).param("detail", "x").build());
        assertThat(failed).isCompletedExceptionally();
        assertThat(runFailures)
                .as("no failures tolerated, but a savepoint does not count")
                .isEmpty();
    }

    @Test
    void onlyAsManyCheckpointsAtOnceAsAllowedAndAPauseBetweenThem() {
        CheckpointSettings settings = new CheckpointSettings(
                true,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofSeconds(8),
                1,
                3,
                3,
                CheckpointSettings.Externalized.RETAIN_ON_CANCELLATION);
        CheckpointCoordinator coordinator = coordinator(settings);
        coordinator.start();
        timer.advance(10_000);
        timer.advance(25_000);
        assertThat(coordinator.inProgress())
                .as("checkpoint 1 is still in progress")
                .containsExactly(1L);
        ackAll(coordinator, 1);
        // waited for the slot; the pause after checkpoint 1 comes first
        assertThat(coordinator.inProgress()).isEmpty();
        timer.advance(7_999);
        assertThat(coordinator.inProgress()).isEmpty();
        timer.advance(1);
        assertThat(coordinator.inProgress()).containsExactly(2L);
    }

    @Test
    void noCheckpointsWhileTasksAreNotAllRunningOrWhenTurnedOff() {
        running = false;
        CheckpointCoordinator coordinator = coordinator(settings(3, 3));
        coordinator.start();
        timer.advance(30_000);
        assertThat(workers.triggers).isEmpty();
        assertThat(coordinator.trigger(CheckpointType.SAVEPOINT, "SAVEPOINT", "x"))
                .isCompletedExceptionally();
        coordinator.stop(JobStatus.CANCELLED);

        running = true;
        CheckpointSettings off = new CheckpointSettings(
                false,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ZERO,
                1,
                3,
                3,
                CheckpointSettings.Externalized.RETAIN_ON_CANCELLATION);
        CheckpointCoordinator disabled = coordinator(off);
        disabled.start();
        timer.advance(60_000);
        assertThat(workers.triggers).as("env.checkpoint.enabled: false").isEmpty();
    }

    @Test
    void numbersContinueAfterTheRestoredCheckpointAndThoseRecorded() {
        store.begin(new CheckpointStore.Checkpoint(
                RUN,
                9,
                "orders",
                1,
                CheckpointType.CHECKPOINT,
                CheckpointStore.Status.FAILED,
                "PERIODIC",
                null,
                java.time.Instant.EPOCH,
                null,
                null,
                "run-1/chk-9/",
                null,
                null,
                null));
        assertThat(coordinator(settings(3, 3), 4, 1024).trigger(CheckpointType.CHECKPOINT, "MANUAL", null))
                .isNotDone();
        assertThat(workers.triggers).extracting(CheckpointTrigger::checkpointId).containsOnly(10L);
        coordinator(settings(3, 3), 20, 1024).trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        assertThat(workers.triggers).extracting(CheckpointTrigger::checkpointId).contains(21L);
    }

    @Test
    void aCheckpointThatCannotBeRecordedIsNeverAnnouncedToTheTasks() throws Exception {
        CheckpointCoordinator coordinator = coordinator(settings(3, 3));
        coordinator.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ALL.subList(0, 2).forEach(task -> coordinator.acknowledge(ack(task, 1)));
        store.down = true;
        coordinator.acknowledge(ack(SINK, 1));
        assertThat(workers.of("complete")).isEmpty();
        assertThat(workers.of("abort")).hasSize(3);
        assertThat(storage.list("run-1/"))
                .as("the written checkpoint is deleted again")
                .isEmpty();
        assertThat(coordinator.stats().failures()).isEqualTo(1);

        store.down = false;
        storage = new FileSystemCheckpointStorage(dir.resolve("file-instead-of-dir"));
        CheckpointCoordinator broken = coordinator(settings(3, 3));
        try {
            java.nio.file.Files.writeString(dir.resolve("file-instead-of-dir"), "x");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        broken.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ackAll(broken, 2);
        assertThat(store.get(RUN, 2).errorCode()).isEqualTo("PF-CKPT-0006");
        assertThat(store.get(RUN, 2).errorMessage()).contains("writing it to");
        assertThat(workers.of("complete")).isEmpty();
    }

    @Test
    void theEndOfTheRunDecidesWhatIsKept() throws Exception {
        CheckpointCoordinator failed = coordinator(settings(3, 3));
        failed.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ackAll(failed, 1);
        failed.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        failed.stop(JobStatus.FAILED);
        assertThat(storage.isComplete("run-1/chk-1/"))
                .as("a failed run keeps its checkpoints")
                .isTrue();
        assertThat(store.get(RUN, 2).status()).isEqualTo(CheckpointStore.Status.FAILED);
        assertThat(failed.stats().failures())
                .as("ending the run is not a failure of the checkpoint")
                .isZero();

        CheckpointCoordinator cancelled = coordinator(settings(3, 3));
        cancelled.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ackAll(cancelled, 3);
        cancelled.stop(JobStatus.CANCELLED);
        assertThat(storage.isComplete("run-1/chk-3/"))
                .as("kept on cancellation by default")
                .isTrue();

        CheckpointCoordinator succeeded = coordinator(settings(3, 3));
        succeeded.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ackAll(succeeded, 4);
        succeeded.stop(JobStatus.SUCCESS);
        assertThat(storage.isComplete("run-1/chk-4/"))
                .as("a run that succeeded needs none")
                .isFalse();
        assertThat(store.get(RUN, 4)).isNull();

        CheckpointSettings delete = new CheckpointSettings(
                true,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ZERO,
                1,
                3,
                3,
                CheckpointSettings.Externalized.DELETE_ON_CANCELLATION);
        CheckpointCoordinator deleting = coordinator(delete);
        deleting.trigger(CheckpointType.CHECKPOINT, "MANUAL", null);
        ackAll(deleting, 5);
        deleting.stop(JobStatus.CANCELLED);
        assertThat(storage.isComplete("run-1/chk-5/")).isFalse();
        deleting.start();
        timer.advance(60_000);
        assertThat(workers.triggers).extracting(CheckpointTrigger::checkpointId).doesNotContain(6L);
    }

    @Test
    void theTasksOfACheckpointAsJson() {
        ExecutionAttemptId odd = new ExecutionAttemptId("r\"1", "a\\b", 0, 0);
        assertThat(CheckpointCoordinator.tasksJson(
                        List.of(new CheckpointAck(odd, 1, List.of(), CheckpointTaskMetrics.NONE))))
                .isEqualTo(
                        "[{\"task\":\"" + odd.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                                + "\",\"states\":0,\"syncMs\":0,\"asyncMs\":0,\"alignmentMs\":0,\"stateBytes\":0}]");
    }
}
