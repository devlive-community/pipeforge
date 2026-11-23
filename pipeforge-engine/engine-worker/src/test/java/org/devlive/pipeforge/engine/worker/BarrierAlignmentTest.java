/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.network.NetworkEvent;
import org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier;
import org.devlive.pipeforge.engine.network.NetworkOptions;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.partition.ResultPartition;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.RunContext;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskSettings;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A task with two input channels aligns the barriers of a checkpoint: a channel that delivered the
 * barrier is not read until the other one delivered it too (protocol specification, section 3.3).
 */
class BarrierAlignmentTest {

    final NetworkEnvironment network =
            new NetworkEnvironment("test", "token", NetworkOptions.defaults().withMemory(8L << 20, 4096));

    {
        network.startLocal();
    }

    @AfterEach
    void stop() {
        network.close();
    }

    /** What reached the first operator of the chain, in order. */
    static final class Recorder implements ChainOutput {
        final List<String> seen = new ArrayList<>();

        @Override
        public void row(Row row) {
            seen.add("row " + row.get(0));
        }

        @Override
        public void schemaChange(SchemaChangeEvent event) {
            seen.add("schema change");
        }

        @Override
        public void barrier(CheckpointBarrier barrier) {
            seen.add("barrier " + barrier.checkpointId());
        }
    }

    JobPlan plan = TestConnectors.job(Probe.create(), "", "", "", 1);
    List<CatalogTable> tables = plan.nodes().get(plan.nodes().size() - 1).inputTables();
    ResultPartition a = partition(0);
    ResultPartition b = partition(1);

    ResultPartition partition(int producer) {
        return network.createPartition(
                new ResultPartitionId("run-1", "src", producer, 0, 0), 1, NetworkOutput.codec(tables));
    }

    NetworkInput input() {
        List<PlannedNode> nodes = plan.nodes();
        ChainDescriptor chain = new ChainDescriptor(
                "out",
                new org.devlive.pipeforge.api.factory.JobContext(plan.name(), 1, plan.timeZone()),
                nodes.subList(nodes.size() - 1, nodes.size()));
        TaskDeployment deployment = new TaskDeployment(
                new ExecutionAttemptId("run-1", "out", 0, 0),
                chain,
                1,
                TaskSettings.defaults(),
                RunContext.of("run-1", Instant.EPOCH),
                null,
                List.of(new TaskDeployment.Input(
                        0,
                        "src",
                        List.of(
                                new TaskDeployment.Channel(new ExecutionAttemptId("run-1", "src", 0, 0), 0, 0, null),
                                new TaskDeployment.Channel(
                                        new ExecutionAttemptId("run-1", "src", 1, 0), 0, 0, null)))));
        return new NetworkInput(network, deployment, tables);
    }

    void emit(ResultPartition partition, long id) throws InterruptedException {
        partition.emit(0, GenericRow.of(tables.get(0).id(), id, "name-" + id), null, () -> false);
        partition.flush();
    }

    static CheckpointBarrier barrier(long id) {
        return new CheckpointBarrier(id, 0, CheckpointBarrier.Type.CHECKPOINT, false);
    }

    /** Polls until nothing more arrives for a while. */
    static void drain(NetworkInput input, Recorder recorder, List<String> aligned) throws InterruptedException {
        int quiet = 0;
        while (quiet < 5) {
            var status = input.poll(recorder, (barrier, nanos) -> {
                aligned.add("aligned " + barrier.checkpointId());
                recorder.barrier(barrier);
            });
            quiet = status == org.devlive.pipeforge.api.source.PollStatus.MORE_AVAILABLE ? 0 : quiet + 1;
        }
    }

    @Test
    void aChannelThatDeliveredTheBarrierWaitsForTheOthers() throws Exception {
        NetworkInput input = input();
        Recorder recorder = new Recorder();
        List<String> aligned = new ArrayList<>();
        emit(a, 1);
        a.emitEvent(0, barrier(7));
        emit(a, 2);
        emit(b, 10);
        drain(input, recorder, aligned);
        assertThat(recorder.seen)
                .as("row 2 came after the barrier of channel a: it waits")
                .containsExactlyInAnyOrder("row 1", "row 10");
        assertThat(input.aligning()).isEqualTo(7);

        b.emitEvent(0, barrier(7));
        emit(b, 11);
        drain(input, recorder, aligned);
        assertThat(aligned).containsExactly("aligned 7");
        assertThat(recorder.seen.subList(2, 3)).containsExactly("barrier 7");
        assertThat(recorder.seen.subList(3, recorder.seen.size())).containsExactlyInAnyOrder("row 2", "row 11");
        assertThat(input.aligning()).isZero();
        input.close();
    }

    @Test
    void anEndedChannelDoesNotHoldTheAlignmentBack() throws Exception {
        NetworkInput input = input();
        Recorder recorder = new Recorder();
        List<String> aligned = new ArrayList<>();
        b.broadcastEvent(NetworkEvent.END_OF_INPUT_EVENT);
        b.finish();
        a.emitEvent(0, barrier(3));
        emit(a, 1);
        drain(input, recorder, aligned);
        assertThat(aligned).containsExactly("aligned 3");
        assertThat(recorder.seen).containsExactly("barrier 3", "row 1");
        input.close();
    }

    @Test
    void anAbortedCheckpointReleasesTheChannelsAndItsLateBarriersAreDropped() throws Exception {
        NetworkInput input = input();
        Recorder recorder = new Recorder();
        List<String> aligned = new ArrayList<>();
        a.emitEvent(0, barrier(4));
        emit(a, 1);
        drain(input, recorder, aligned);
        assertThat(recorder.seen).isEmpty();

        input.abort(4);
        drain(input, recorder, aligned);
        assertThat(recorder.seen).containsExactly("row 1");
        b.emitEvent(0, barrier(4));
        emit(b, 2);
        drain(input, recorder, aligned);
        assertThat(aligned).as("the late barrier of checkpoint 4").isEmpty();

        // a newer checkpoint aligns as usual; a barrier of an older one that started aligning is given up
        a.emitEvent(0, barrier(5));
        b.emitEvent(0, barrier(6));
        a.emitEvent(0, barrier(6));
        emit(a, 3);
        drain(input, recorder, aligned);
        assertThat(aligned).containsExactly("aligned 6");
        assertThat(recorder.seen).endsWith("barrier 6", "row 3");
        input.close();
    }
}
