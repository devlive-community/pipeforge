/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.network.NetworkOptions;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.Partitioning;
import org.devlive.pipeforge.engine.rpc.RunContext;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskSettings;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Chains that read from and write to other tasks of the same worker. */
class NetworkChainTest {

    final NetworkEnvironment network =
            new NetworkEnvironment("test", "token", NetworkOptions.defaults().withMemory(8L << 20, 4096));

    {
        network.startLocal();
    }

    @AfterEach
    void stop() {
        network.close();
    }

    static final RunContext RUN = RunContext.of("run-1", Instant.EPOCH);
    static final ExecutionAttemptId PRODUCER = new ExecutionAttemptId("run-1", "src", 0, 0);

    static JobContext job(JobPlan plan) {
        return new JobContext(plan.name(), 1, plan.timeZone());
    }

    /** The source and transform of {@code plan}, writing to {@code consumers} sink tasks. */
    static TaskDeployment producer(JobPlan plan, int consumers) {
        List<PlannedNode> nodes = plan.nodes();
        ChainDescriptor chain = new ChainDescriptor(
                "src",
                job(plan),
                nodes.subList(0, nodes.size() - 1),
                List.of(new ChainDescriptor.Output(0, "out", Partitioning.REBALANCE, consumers)));
        return new TaskDeployment(PRODUCER, chain, 1, TaskSettings.defaults(), RUN);
    }

    /** Sink task {@code subtask} of {@code consumers}, reading its subpartition of the producer. */
    static TaskDeployment consumer(JobPlan plan, int subtask, int consumers) {
        List<PlannedNode> nodes = plan.nodes();
        ChainDescriptor chain = new ChainDescriptor("out", job(plan), nodes.subList(nodes.size() - 1, nodes.size()));
        return new TaskDeployment(
                new ExecutionAttemptId("run-1", "out", subtask, 0),
                chain,
                consumers,
                TaskSettings.defaults(),
                RUN,
                null,
                List.of(new TaskDeployment.Input(
                        0, "src", List.of(new TaskDeployment.Channel(PRODUCER, 0, subtask, null)))));
    }

    /** The final states of {@code count} tasks, by vertex and subtask. */
    static Map<String, TaskStateUpdate> ends(ScriptedMaster master, int count) throws InterruptedException {
        Map<String, TaskStateUpdate> ends = new HashMap<>();
        while (ends.size() < count) {
            TaskStateUpdate update = master.updates.poll(20, TimeUnit.SECONDS);
            assertThat(update).as("tasks end").isNotNull();
            if (update.state().isTerminal()) {
                ends.put(update.id().vertexId() + "/" + update.id().subtask(), update);
            }
        }
        return ends;
    }

    @Test
    void aSourceTaskRebalancesItsRowsOverTwoSinkTasks() throws Exception {
        String probe = Probe.create();
        JobPlan plan = TestConnectors.job(probe, "", "", "", 1);
        try (ScriptedMaster master = new ScriptedMaster(
                List.of(TestConnectors.split("a", 0, 5), TestConnectors.split("b", 5, 10)), null, network)) {
            master.executor.deployTask(consumer(plan, 0, 2)).get(5, TimeUnit.SECONDS);
            master.executor.deployTask(consumer(plan, 1, 2)).get(5, TimeUnit.SECONDS);
            master.executor.deployTask(producer(plan, 2)).get(5, TimeUnit.SECONDS);

            Map<String, TaskStateUpdate> ends = ends(master, 3);
            assertThat(ends.values()).allSatisfy(end -> assertThat(end.state())
                    .as(String.valueOf(end.error()))
                    .isEqualTo(TaskState.FINISHED));
            assertThat(Probe.get(probe).rows).hasSize(10);
            assertThat(Probe.get(probe).rowsBySubtask.get(0)).hasSize(5);
            assertThat(Probe.get(probe).rowsBySubtask.get(1)).hasSize(5);
            assertThat(ends.get("src/0").metrics().operators())
                    .extracting(o -> o.node())
                    .containsExactly("src", "up");
            assertThat(ends.get("out/1").metrics().operators()).singleElement().satisfies(o -> {
                assertThat(o.node()).isEqualTo("out");
                assertThat(o.rowsIn()).isEqualTo(5);
            });
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (network.partitions().size() > 0) {
            assertThat(System.nanoTime()).as("the output is released").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void aSchemaChangeCannotCrossAnExchange() throws Exception {
        String probe = Probe.create();
        JobPlan plan = TestConnectors.job(probe, "schema-change: true", "", "", 1);
        try (ScriptedMaster master = new ScriptedMaster(List.of(TestConnectors.split("a", 0, 5)), null, network)) {
            master.executor.deployTask(consumer(plan, 0, 1)).get(5, TimeUnit.SECONDS);
            master.executor.deployTask(producer(plan, 1)).get(5, TimeUnit.SECONDS);
            Map<String, TaskStateUpdate> ends = ends(master, 1);
            TaskStateUpdate source = ends.get("src/0");
            assertThat(source.state()).isEqualTo(TaskState.FAILED);
            assertThat(source.error().code()).isEqualTo(ErrorCode.TOPOLOGY_UNSUPPORTED);
            assertThat(source.error().getMessage()).contains("schema changes cannot cross a data exchange");
            master.executor.cancelTask(new ExecutionAttemptId("run-1", "out", 0, 0));
            assertThat(ends(master, 1).get("out/0").state()).isEqualTo(TaskState.CANCELED);
        }
    }

    @Test
    void aWorkerWithoutADataExchangeRefusesChainsThatNeedOne() throws Exception {
        JobPlan plan = TestConnectors.job(Probe.create(), "", "", "", 1);
        try (ScriptedMaster master = ScriptedMaster.tenRows()) {
            master.executor.deployTask(producer(plan, 1)).get(5, TimeUnit.SECONDS);
            TaskStateUpdate end = master.awaitEnd();
            assertThat(end.state()).isEqualTo(TaskState.FAILED);
            assertThat(end.error().code()).isEqualTo(ErrorCode.TOPOLOGY_UNSUPPORTED);
            assertThat(end.error().getMessage()).contains("this worker has no data exchange");
        }
    }

    @Test
    void aTaskWaitingForItsInputCanBeCancelled() throws Exception {
        JobPlan plan = TestConnectors.job(Probe.create(), "", "", "", 1);
        try (ScriptedMaster master = new ScriptedMaster(List.of(), null, network)) {
            master.executor.deployTask(consumer(plan, 0, 1)).get(5, TimeUnit.SECONDS);
            master.awaitRunning();
            long started = System.nanoTime();
            master.executor.cancelTask(new ExecutionAttemptId("run-1", "out", 0, 0));
            assertThat(master.awaitEnd().state()).isEqualTo(TaskState.CANCELED);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }
}
