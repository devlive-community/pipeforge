/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskSettings;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.Test;

class HeapGuardTest {

    /** Metrics every 20 ms. */
    private static final TaskSettings OFTEN =
            new TaskSettings(true, 1024, 4, Duration.ofSeconds(30), Duration.ofMillis(20));

    /** A heap of 100 bytes that the test fills by hand. */
    private static final class FakeHeap implements HeapGuard.Heap {
        final AtomicLong used = new AtomicLong();

        @Override
        public long used() {
            return used.get();
        }

        @Override
        public long max() {
            return 100;
        }
    }

    /** Every row goes to the write thread at once, so the write buffer holds nothing for long. */
    private static final TaskSettings UNBATCHED =
            new TaskSettings(true, 1, 4, Duration.ofSeconds(30), Duration.ofMillis(20));

    /** The plan deployed as subtask 0 of its first node in {@code run}. */
    private static TaskDeployment deployment(String run, JobPlan plan) {
        return deployment(run, plan, OFTEN);
    }

    private static TaskDeployment deployment(String run, JobPlan plan, TaskSettings settings) {
        List<PlannedNode> nodes = plan.nodes();
        ChainDescriptor chain = new ChainDescriptor(
                nodes.get(0).id(),
                new org.devlive.pipeforge.api.factory.JobContext(plan.name(), 1, plan.timeZone()),
                nodes);
        return new TaskDeployment(
                new ExecutionAttemptId(run, nodes.get(0).id(), 0, 0),
                chain,
                1,
                settings,
                org.devlive.pipeforge.engine.rpc.RunContext.of(run, Instant.EPOCH));
    }

    private static ScriptedMaster master() {
        // a task holds at most about 2KB of rows for its sink
        return new ScriptedMaster(
                List.of(
                        TestConnectors.split("a", 0, 5),
                        TestConnectors.split("b", 5, 10),
                        TestConnectors.split("c", 10, 400),
                        TestConnectors.split("d", 400, 800)),
                null,
                null,
                null,
                TaskExecutor.Options.of(new MemoryLimits(64L * 1024 * 1024, 2_000)));
    }

    @Test
    void theRunHoldingTheMostStopsReadingAndThenFailsWhileTheOtherRunsGoOn() throws Exception {
        String bloated = Probe.create();
        String small = Probe.create();
        try (ScriptedMaster master = master()) {
            FakeHeap heap = new FakeHeap();
            AtomicLong clock = new AtomicLong(1);
            HeapGuard guard = master.executor.guard(HeapGuard.Settings.DEFAULTS, heap, clock::get);

            // run-1 piles rows of about 450 bytes up for a sink that blocks; run-2 writes its rows at once
            master.executor
                    .deployTask(deployment("run-1", TestConnectors.job(bloated, "pad: 200", "", "block: cancel", 1)))
                    .get();
            assertThat(Probe.get(bloated).blocking.await(10, TimeUnit.SECONDS)).isTrue();
            master.executor
                    .deployTask(deployment("run-2", TestConnectors.job(small, "endless: true", "", "", 1), UNBATCHED))
                    .get();
            awaitAccounted(master.executor, "run-1");
            awaitState(master.executor, "run-2", TaskState.RUNNING);

            heap.used.set(50);
            guard.check();
            assertThat(guard.pausedRun()).isNull();

            heap.used.set(88);
            guard.check();
            assertThat(guard.pausedRun()).isEqualTo("run-1");
            guard.check();
            assertThat(guard.actions()).containsEntry("pause", 1L).containsEntry("fail", 0L);

            // at the failure line, but not for long enough yet
            heap.used.set(95);
            guard.check();
            clock.addAndGet(TimeUnit.SECONDS.toNanos(9));
            guard.check();
            assertThat(master.executor.taskStates()).hasSize(2);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            guard.check();

            TaskStateUpdate end = master.awaitEnd();
            assertThat(end.id().runId()).isEqualTo("run-1");
            assertThat(end.state()).isEqualTo(TaskState.FAILED);
            assertThat(end.error().code()).isEqualTo(ErrorCode.OUT_OF_MEMORY);
            assertThat(end.error().getMessage())
                    .contains("run-1")
                    .contains("92%")
                    .contains("10 s");
            assertThat(end.error().scope().run()).isEqualTo("run-1");
            assertThat(end.metrics().memoryBytes()).as("what it held is freed").isZero();
            assertThat(guard.actions()).containsEntry("fail", 1L);
            assertThat(guard.pausedRun()).isNull();

            // the other run is untouched
            assertThat(master.executor.taskStates())
                    .containsOnlyKeys(new ExecutionAttemptId("run-2", "src", 0, 0))
                    .containsValue(TaskState.RUNNING);
        }
    }

    @Test
    void aHeldBackSourceReadsNothingUntilTheHeapIsLowerAgain() throws Exception {
        String probe = Probe.create();
        try (ScriptedMaster master = master()) {
            FakeHeap heap = new FakeHeap();
            HeapGuard guard = master.executor.guard(HeapGuard.Settings.DEFAULTS, heap, System::nanoTime);
            // a slow sink keeps a few rows waiting in the write buffer
            master.executor
                    .deployTask(deployment("run-1", TestConnectors.job(probe, "pad: 200", "", "slow-ms: 5", 1)))
                    .get();
            awaitAccounted(master.executor, "run-1");

            heap.used.set(90);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (guard.pausedRun() == null) {
                assertThat(System.nanoTime()).as("the run is paused").isLessThan(deadline);
                guard.check();
                Thread.sleep(5);
            }
            // the rows read before stay; nothing more is read once the buffer drained
            Thread.sleep(300);
            long held = rowsRead(master);
            Thread.sleep(300);
            assertThat(rowsRead(master)).isEqualTo(held).isLessThan(800);

            heap.used.set(40);
            guard.check();
            assertThat(guard.pausedRun()).isNull();
            TaskStateUpdate end = master.awaitEnd();
            assertThat(end.state()).isEqualTo(TaskState.FINISHED);
            assertThat(end.metrics().operators().get(0).rowsOut()).isEqualTo(800);
            assertThat(guard.actions()).containsEntry("pause", 1L).containsEntry("fail", 0L);
        }
    }

    @Test
    void doesNothingWhenNoTaskAccountsMemoryOrWhenDisabled() throws Exception {
        String probe = Probe.create();
        try (ScriptedMaster master = master()) {
            FakeHeap heap = new FakeHeap();
            heap.used.set(99);
            AtomicLong clock = new AtomicLong(1);
            HeapGuard guard = master.executor.guard(HeapGuard.Settings.DEFAULTS, heap, clock::get);
            HeapGuard off = master.executor.guard(HeapGuard.Settings.DISABLED, heap, clock::get);
            // no task: no run to blame
            for (int i = 0; i < 3; i++) {
                guard.check();
                clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
            }
            assertThat(guard.actions()).containsEntry("pause", 0L).containsEntry("fail", 0L);

            master.executor
                    .deployTask(deployment("run-1", TestConnectors.job(probe, "pad: 200", "", "block: cancel", 1)))
                    .get();
            awaitAccounted(master.executor, "run-1");
            for (int i = 0; i < 3; i++) {
                off.check();
                clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
            }
            assertThat(off.actions()).containsEntry("pause", 0L).containsEntry("fail", 0L);
            assertThat(master.executor.taskStates()).containsValue(TaskState.RUNNING);
            // a worker without the guard set runs none
            assertThat(master.executor.heapGuardActions()).isEqualTo(Map.of("pause", 0L, "fail", 0L));
        }
    }

    @Test
    void settingsMustBeOrdered() {
        assertThatThrownBy(() -> new HeapGuard.Settings(true, 95, 90, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pause percent <= fail percent");
        assertThatThrownBy(() -> new HeapGuard.Settings(true, 0, 90, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HeapGuard.Settings(true, 80, 101, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HeapGuard.Settings(true, 80, 90, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(HeapGuard.Heap.jvm().max()).isPositive();
        assertThat(HeapGuard.Heap.jvm().used()).isPositive();
    }

    /** The rows the source read, from the latest metrics of the task. */
    private static long rowsRead(ScriptedMaster master) throws InterruptedException {
        TaskMetricsSnapshot latest = master.metrics.poll(5, TimeUnit.SECONDS);
        assertThat(latest).isNotNull();
        TaskMetricsSnapshot next;
        while ((next = master.metrics.poll()) != null) {
            latest = next;
        }
        return latest.operators().get(0).rowsOut();
    }

    private static void awaitAccounted(TaskExecutor executor, String run) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (executor.accountedMemory() == 0) {
            assertThat(System.nanoTime()).as("%s holds rows", run).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private static void awaitState(TaskExecutor executor, String run, TaskState state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (executor.taskStates().entrySet().stream()
                .noneMatch(e -> e.getKey().runId().equals(run) && e.getValue() == state)) {
            assertThat(System.nanoTime()).as("%s is %s", run, state).isLessThan(deadline);
            Thread.sleep(5);
        }
    }
}
