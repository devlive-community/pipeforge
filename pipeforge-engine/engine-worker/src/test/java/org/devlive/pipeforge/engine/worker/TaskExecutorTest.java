/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.AddColumn;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskSettings;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.Test;

class TaskExecutorTest {

    private static TaskStateUpdate run(JobPlan plan, TaskSettings settings) throws Exception {
        try (ScriptedMaster master = ScriptedMaster.tenRows()) {
            master.executor
                    .deployTask(ScriptedMaster.deployment(plan, settings))
                    .get();
            return master.awaitEnd();
        }
    }

    private static TaskStateUpdate run(JobPlan plan) throws Exception {
        return run(plan, ScriptedMaster.settings());
    }

    private static List<Long> ids(Probe probe) {
        return probe.rows.stream().map(r -> (Long) r.get(0)).sorted().toList();
    }

    @Test
    void runsTheChainOnTheTaskThreadAndWritesOnTheWriteThread() throws Exception {
        String probe = Probe.create();
        TaskStateUpdate end = run(TestConnectors.job(probe, "", "", "", 1));

        assertThat(end.state()).isEqualTo(TaskState.FINISHED);
        assertThat(end.committables()).isEmpty();
        Probe seen = Probe.get(probe);
        assertThat(ids(seen)).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(seen.rows).allSatisfy(r -> assertThat((String) r.get(1)).startsWith("NAME-"));
        assertThat(seen.readerThreads).containsExactly("task-src-0");
        assertThat(seen.writerThreads).containsExactly("writer-src-0");
        assertThat(seen.events).contains("transform opened 0/1 1", "reader 0 closed");
    }

    @Test
    void writesOnTheTaskThreadWithoutTheBuffer() throws Exception {
        String probe = Probe.create();
        TaskSettings direct = new TaskSettings(false, 1024, 4, Duration.ofSeconds(30));
        TaskStateUpdate end = run(TestConnectors.job(probe, "", "drop-odd: true", "", 1), direct);

        assertThat(end.state()).isEqualTo(TaskState.FINISHED);
        assertThat(ids(Probe.get(probe))).containsExactly(0L, 2L, 4L, 6L, 8L);
        assertThat(Probe.get(probe).writerThreads).containsExactly("task-src-0");
    }

    @Test
    void transformsCanEmitSeveralRowsAndBatchesCanBeSmall() throws Exception {
        String probe = Probe.create();
        TaskSettings tiny = new TaskSettings(true, 3, 1, Duration.ofSeconds(30));
        TaskStateUpdate end = run(TestConnectors.job(probe, "", "duplicate: true", "", 1), tiny);

        assertThat(end.state()).isEqualTo(TaskState.FINISHED);
        assertThat(Probe.get(probe).rows).hasSize(20);
    }

    @Test
    void passesSchemaChangesThroughTheChainInOrder() throws Exception {
        String probe = Probe.create();
        TaskStateUpdate end = run(TestConnectors.job(probe, "schema-change: true", "", "", 1));

        assertThat(end.state()).isEqualTo(TaskState.FINISHED);
        Probe seen = Probe.get(probe);
        assertThat(seen.schemaChanges).singleElement().isInstanceOf(AddColumn.class);
        assertThat(seen.events).contains("transform saw AddColumn");
    }

    @Test
    void returnsTheCommittableOfTheSinkWhenTheInputEnds() throws Exception {
        String probe = Probe.create();
        TaskStateUpdate end = run(TestConnectors.job(probe, "", null, "commit: two-phase", 1));

        assertThat(end.state()).isEqualTo(TaskState.FINISHED);
        assertThat(end.committables()).singleElement().satisfies(value -> assertThat(
                        new String(value.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("0:10@" + Task.FINAL_CHECKPOINT_ID));
    }

    @Test
    void reportsSourceFailuresWithTheNodeAndSubtask() throws Exception {
        String probe = Probe.create();
        TaskStateUpdate end = run(TestConnectors.job(probe, "fail-at: 3", "", "", 1));

        assertThat(end.state()).isEqualTo(TaskState.FAILED);
        PipeForgeException error = end.error();
        assertThat(error.code().id()).isEqualTo("PF-SRC-0001");
        assertThat(error.params()).containsEntry("node", "src");
        assertThat((String) error.params().get("reason")).isEqualTo("read: IllegalStateException: reading row 3 broke");
        assertThat(error.scope().node()).isEqualTo("src");
        assertThat(error.scope().subtask()).isZero();
        assertThat(error.scope().run()).isEqualTo("run-1");
        assertThat(error.scope().job()).isEqualTo("test-job");
        assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(Probe.get(probe).events).contains("reader 0 closed");
    }

    @Test
    void reportsTransformFailuresAsPluginCallFailures() throws Exception {
        String probe = Probe.create();
        TaskStateUpdate end = run(TestConnectors.job(probe, "", "fail-at: 2, fail-with: io", "", 1));

        PipeForgeException error = end.error();
        assertThat(error.code().id()).isEqualTo("PF-PLUG-0007");
        assertThat(error.params())
                .containsEntry("plugin", "builtin")
                .containsEntry("operation", "process a row")
                .containsEntry("reason", "IOException: transforming row 2 broke");
        assertThat(error.scope().node()).isEqualTo("up");
    }

    @Test
    void reportsSinkFailuresFromTheWriteThread() throws Exception {
        String probe = Probe.create();
        TaskStateUpdate end = run(TestConnectors.job(probe, "", null, "fail-at: 4", 1));

        PipeForgeException error = end.error();
        assertThat(error.code().id()).isEqualTo("PF-SINK-0001");
        assertThat((String) error.params().get("reason"))
                .isEqualTo("write a row: IllegalStateException: writing row 4 broke");
        assertThat(error.scope().node()).isEqualTo("out");
    }

    @Test
    void reportsSinkFailuresOnTheTaskThreadWithoutTheBuffer() throws Exception {
        String probe = Probe.create();
        TaskSettings direct = new TaskSettings(false, 1024, 4, Duration.ofSeconds(30));
        TaskStateUpdate end = run(TestConnectors.job(probe, "", null, "fail-at: 0", 1), direct);

        assertThat(end.error().code().id()).isEqualTo("PF-SINK-0001");
    }

    @Test
    void keepsTheCodeOfPipeForgeErrorsAndAddsTheNode() throws Exception {
        String probe = Probe.create();
        TaskStateUpdate end = run(TestConnectors.job(probe, "", "fail-at: 1, fail-with: coded", "", 1));

        PipeForgeException error = end.error();
        assertThat(error.code().id()).isEqualTo("PF-DATA-0006");
        assertThat(error.params()).containsEntry("expression", "transforming row 1");
        assertThat(error.scope().node()).isEqualTo("up");
        assertThat(error.scope().subtask()).isZero();
        assertThat(error.scope().run()).isEqualTo("run-1");
    }

    @Test
    void cancelWakesABlockedReaderThroughItsCancelHook() throws Exception {
        String probe = Probe.create();
        try (ScriptedMaster master = ScriptedMaster.tenRows()) {
            master.executor
                    .deployTask(ScriptedMaster.deployment(
                            TestConnectors.job(probe, "block: cancel", "", "", 1), ScriptedMaster.settings()))
                    .get();
            assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
            master.executor.cancelTask(master.id()).get();

            assertThat(master.awaitEnd().state()).isEqualTo(TaskState.CANCELED);
            assertThat(Probe.get(probe).events).contains("reader 0 closed");
        }
    }

    @Test
    void interruptsATaskThatIgnoresItsCancelHook() throws Exception {
        String probe = Probe.create();
        TaskSettings quick = new TaskSettings(true, 1024, 4, Duration.ofMillis(100));
        try (ScriptedMaster master = ScriptedMaster.tenRows()) {
            master.executor
                    .deployTask(
                            ScriptedMaster.deployment(TestConnectors.job(probe, "block: interrupt", "", "", 1), quick))
                    .get();
            assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            master.executor.cancelTask(master.id()).get();

            assertThat(master.awaitEnd().state()).isEqualTo(TaskState.CANCELED);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(90));
        }
    }

    @Test
    void givesUpATaskThatIgnoresCancelAndInterrupts() throws Exception {
        String probe = Probe.create();
        TaskSettings quick = new TaskSettings(true, 1024, 4, Duration.ofMillis(50));
        try (ScriptedMaster master = ScriptedMaster.tenRows()) {
            master.executor
                    .deployTask(
                            ScriptedMaster.deployment(TestConnectors.job(probe, "block: forever", "", "", 1), quick))
                    .get();
            assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
            master.executor.cancelTask(master.id()).get();

            TaskStateUpdate end = master.awaitEnd();
            assertThat(end.state()).isEqualTo(TaskState.FAILED);
            assertThat(end.error().code().id()).isEqualTo("PF-RES-0005");
            assertThat(end.error().params())
                    .containsEntry("node", "src")
                    .containsEntry("subtask", 0)
                    .containsEntry("timeoutMs", 100L);
            assertThat(master.executor.runningTasks()).isEmpty();
            Probe.get(probe).release.countDown();
        }
    }

    @Test
    void cancelInterruptsAWriterBlockedInTheTarget() throws Exception {
        String probe = Probe.create();
        try (ScriptedMaster master = ScriptedMaster.tenRows()) {
            master.executor
                    .deployTask(ScriptedMaster.deployment(
                            TestConnectors.job(probe, "", null, "block: cancel", 1),
                            new TaskSettings(true, 1, 1, Duration.ofSeconds(30))))
                    .get();
            assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
            master.executor.cancelTask(master.id()).get();

            assertThat(master.awaitEnd().state()).isEqualTo(TaskState.CANCELED);
        }
    }

    @Test
    void ignoresEventsAndCancelsForUnknownTasksAndRejectsDuplicates() throws Exception {
        String probe = Probe.create();
        try (ScriptedMaster master = ScriptedMaster.tenRows()) {
            master.executor
                    .sendOperatorEvent(master.id(), new OperatorEvent.NoMoreSplits("src"))
                    .get();
            master.executor.cancelTask(master.id()).get();
            TaskDeployment deployment = ScriptedMaster.deployment(
                    TestConnectors.job(probe, "block: cancel", "", "", 1), ScriptedMaster.settings());
            master.executor.deployTask(deployment).get();
            CompletableFuture<Void> again = master.executor.deployTask(deployment);

            assertThat(again).isCompletedExceptionally();
            master.awaitRunning();
            assertThat(master.executor.runningTasks()).containsExactly(master.id());
            master.executor.cancelTask(master.id()).get();
            assertThat(master.awaitEnd().state()).isEqualTo(TaskState.CANCELED);
        }
    }

    @Test
    void endsWithoutSplitsWhenTheMasterHasNone() throws Exception {
        String probe = Probe.create();
        try (ScriptedMaster master = new ScriptedMaster(List.<SerializedValue>of())) {
            master.executor
                    .deployTask(ScriptedMaster.deployment(
                            TestConnectors.job(probe, "", null, "", 1), ScriptedMaster.settings()))
                    .get();
            TaskStateUpdate end = master.awaitEnd();

            assertThat(end.state()).isEqualTo(TaskState.FINISHED);
            List<Row> rows = Probe.get(probe).rows;
            assertThat(rows).isEmpty();
        }
    }

    @Test
    void closingTheExecutorCancelsItsTasks() throws Exception {
        String probe = Probe.create();
        ScriptedMaster master = ScriptedMaster.tenRows();
        master.executor
                .deployTask(ScriptedMaster.deployment(
                        TestConnectors.job(probe, "block: cancel", "", "", 1), ScriptedMaster.settings()))
                .get();
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        master.executor.close();

        assertThat(master.awaitEnd().state()).isEqualTo(TaskState.CANCELED);
        master.close();
    }
}
