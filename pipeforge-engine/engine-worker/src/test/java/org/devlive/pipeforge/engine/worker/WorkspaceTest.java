/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskSettings;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Work directories of runs on a worker and the disk quota of a job. */
class WorkspaceTest {

    @TempDir
    Path dir;

    private static TaskDeployment deployment(String run, JobPlan plan) {
        List<PlannedNode> nodes = plan.nodes();
        ChainDescriptor chain = new ChainDescriptor(
                nodes.get(0).id(),
                new org.devlive.pipeforge.api.factory.JobContext(plan.name(), 1, plan.timeZone()),
                nodes);
        return new TaskDeployment(
                new ExecutionAttemptId(run, nodes.get(0).id(), 0, 0),
                chain,
                1,
                TaskSettings.defaults(),
                org.devlive.pipeforge.engine.rpc.RunContext.of(run, Instant.EPOCH));
    }

    private ScriptedMaster master(long quota, org.devlive.pipeforge.engine.worker.log.LogService logs) {
        return new ScriptedMaster(
                List.of(TestConnectors.split("a", 0, 5), TestConnectors.split("b", 5, 10)),
                null,
                null,
                logs,
                TaskExecutor.Options.DEFAULTS.withWorkspace(new Workspace.Settings(dir.resolve("jobs"), quota)));
    }

    @Test
    void aRunOverItsQuotaFailsWhileTheOtherRunsGoOn() throws Exception {
        String big = Probe.create();
        String small = Probe.create();
        try (ScriptedMaster master = master(20_000, null)) {
            // run-1 spills 50KB and then blocks; run-2 reads on without writing to disk
            master.executor
                    .deployTask(
                            deployment("run-1", TestConnectors.job(big, "", "", "disk-bytes: 50000, block: cancel", 1)))
                    .get();
            assertThat(Probe.get(big).blocking.await(10, TimeUnit.SECONDS)).isTrue();
            master.executor
                    .deployTask(deployment("run-2", TestConnectors.job(small, "endless: true", "", "", 1)))
                    .get();
            awaitRunning(master.executor, "run-2");

            Path spill = Probe.get(big).workFiles.get(0);
            assertThat(spill)
                    .hasParent(dir.resolve("jobs")
                            .resolve("run-1")
                            .resolve("src-0-0")
                            .resolve("out"));
            assertThat(master.executor.diskUsage())
                    .containsEntry("run-1", 50_000L)
                    .containsEntry("run-2", 0L);

            master.executor.workspace().check();
            TaskStateUpdate end = master.awaitEnd();
            assertThat(end.id().runId()).isEqualTo("run-1");
            assertThat(end.state()).isEqualTo(TaskState.FAILED);
            assertThat(end.error().code()).isEqualTo(ErrorCode.DISK_QUOTA_EXCEEDED);
            assertThat(end.error().getMessage())
                    .contains("run-1")
                    .contains("50000")
                    .contains("20000");
            assertThat(end.error().scope().run()).isEqualTo("run-1");

            // the run's directory went with its last task; the other run is untouched
            awaitGone(dir.resolve("jobs").resolve("run-1"));
            master.executor.workspace().check();
            assertThat(master.executor.taskStates())
                    .containsOnlyKeys(new ExecutionAttemptId("run-2", "src", 0, 0))
                    .containsValue(TaskState.RUNNING);
        }
    }

    @Test
    void theQuotaCountsTheTaskLogsOfTheRun() throws Exception {
        String probe = Probe.create();
        var logs = new org.devlive.pipeforge.engine.worker.log.LogService(
                "worker-1", org.devlive.pipeforge.engine.worker.log.LogService.Settings.defaults(dir.resolve("logs")));
        try (ScriptedMaster master = master(1, logs)) {
            master.executor
                    .deployTask(deployment("run-1", TestConnectors.job(probe, "", "", "block: cancel", 1)))
                    .get();
            assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
            // the task logged that it runs: already more than one byte
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (master.executor.diskUsage().getOrDefault("run-1", 0L) == 0) {
                assertThat(System.nanoTime()).as("the log counts").isLessThan(deadline);
                Thread.sleep(10);
            }
            master.executor.workspace().check();
            TaskStateUpdate end = master.awaitEnd();
            assertThat(end.error().code()).isEqualTo(ErrorCode.DISK_QUOTA_EXCEEDED);
            // the log stays readable after the run: it is not deleted with the work directory
            assertThat(logs.path("test-job", end.id())).exists();
        } finally {
            logs.close();
        }
    }

    @Test
    void aTaskWithinItsQuotaKeepsItsDirectoryUntilItEnds() throws Exception {
        String probe = Probe.create();
        Files.createDirectories(dir.resolve("jobs").resolve("old-run"));
        try (ScriptedMaster master = master(1_000_000, null)) {
            assertThat(dir.resolve("jobs").resolve("old-run"))
                    .as("directories a stopped worker left are removed")
                    .doesNotExist();
            master.executor
                    .deployTask(deployment("run-1", TestConnectors.job(probe, "", "", "disk-bytes: 1000", 1)))
                    .get();
            TaskStateUpdate end = master.awaitEnd();
            assertThat(end.state()).isEqualTo(TaskState.FINISHED);
            assertThat(Probe.get(probe).rows).hasSize(10);
            assertThat(Probe.get(probe).workFiles).hasSize(1);
            awaitGone(dir.resolve("jobs").resolve("run-1"));
        }
    }

    @Test
    void localRunsGetATemporaryDirectoryWithoutQuota() throws Exception {
        String probe = Probe.create();
        try (ScriptedMaster master = ScriptedMaster.tenRows()) {
            master.executor
                    .deployTask(ScriptedMaster.deployment(
                            TestConnectors.job(probe, "", "", "disk-bytes: 1000", 1), ScriptedMaster.settings()))
                    .get();
            assertThat(master.awaitEnd().state()).isEqualTo(TaskState.FINISHED);
            Path spill = Probe.get(probe).workFiles.get(0);
            assertThat(spill.toString()).contains("pipeforge-work-");
            awaitGone(spill.getParent());
            master.executor.workspace().check();
        }
    }

    @Test
    void settingsAndNames() {
        assertThatThrownBy(() -> new Workspace.Settings(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Workspace.safe("a/b:c")).isEqualTo("a_b_c");
        assertThat(Workspace.safe("..")).isEqualTo("_..");
        assertThat(Workspace.size(dir.resolve("missing"))).isZero();
    }

    private static void awaitGone(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Files.exists(path)) {
            assertThat(System.nanoTime()).as("%s is deleted", path).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private static void awaitRunning(TaskExecutor executor, String run) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (executor.taskStates().entrySet().stream()
                .noneMatch(e -> e.getKey().runId().equals(run) && e.getValue() == TaskState.RUNNING)) {
            assertThat(System.nanoTime()).as("%s runs", run).isLessThan(deadline);
            Thread.sleep(5);
        }
    }
}
