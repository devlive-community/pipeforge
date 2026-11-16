/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.junit.jupiter.api.Test;

class ChainDescriptorTest {

    private static final JobContext JOB = JobContext.local("job");

    private static PlannedNode node(String id, NodeKind kind) {
        Component component = new Component() {
            @Override
            public Factory factory() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String plugin() {
                return "builtin";
            }

            @Override
            public ClassLoader classLoader() {
                return getClass().getClassLoader();
            }

            @Override
            public <T> T call(String operation, Callable<T> action) {
                throw new UnsupportedOperationException();
            }
        };
        return new PlannedNode(
                id,
                kind,
                "t",
                "builtin",
                List.of(),
                false,
                1,
                null,
                ReadonlyConfig.empty(),
                List.of(),
                List.of(),
                List.of(),
                component);
    }

    @Test
    void splitsTheChainIntoSourceTransformsAndSink() {
        PlannedNode src = node("src", NodeKind.SOURCE);
        PlannedNode map = node("map", NodeKind.TRANSFORM);
        PlannedNode out = node("out", NodeKind.SINK);
        ChainDescriptor chain = new ChainDescriptor("src", JOB, List.of(src, map, out));

        assertThat(chain.source()).isSameAs(src);
        assertThat(chain.transforms()).containsExactly(map);
        assertThat(chain.sink()).isSameAs(out);
        assertThat(new ChainDescriptor("src", JOB, List.of(src, out)).transforms())
                .isEmpty();
    }

    @Test
    void chainsStartWithASourceOrAnInputAndEndWithASinkOrOutputs() {
        PlannedNode src = node("src", NodeKind.SOURCE);
        PlannedNode up = node("up", NodeKind.TRANSFORM);
        PlannedNode out = node("out", NodeKind.SINK);
        ChainDescriptor.Output edge = new ChainDescriptor.Output(0, "out", Partitioning.REBALANCE, 3);

        ChainDescriptor head = new ChainDescriptor("src", JOB, List.of(src, up), List.of(edge));
        assertThat(head.hasSource()).isTrue();
        assertThat(head.hasSink()).isFalse();
        assertThat(head.transforms()).containsExactly(up);
        assertThatThrownBy(head::sink).hasMessageContaining("writes to the network");
        ChainDescriptor tail = new ChainDescriptor("out", JOB, List.of(out));
        assertThat(tail.hasSource()).isFalse();
        assertThat(tail.sink()).isEqualTo(out);
        assertThatThrownBy(tail::source).hasMessageContaining("reads from the network");

        assertThatThrownBy(() -> new ChainDescriptor("src", JOB, List.of())).hasMessageContaining("at least one node");
        assertThatThrownBy(() -> new ChainDescriptor("up", JOB, List.of(up, src)))
                .hasMessageContaining("A source can only start a chain");
        assertThatThrownBy(() -> new ChainDescriptor("src", JOB, List.of(src, out, up), List.of(edge)))
                .hasMessageContaining("A sink can only end a chain");
        assertThatThrownBy(() -> new ChainDescriptor("src", JOB, List.of(src, up)))
                .hasMessageContaining("must end with a sink or write to outputs");
        assertThatThrownBy(() -> new ChainDescriptor("src", JOB, List.of(src, out), List.of(edge)))
                .hasMessageContaining("ends with a sink has no outputs");
        assertThatThrownBy(() -> new ChainDescriptor.Output(0, "out", Partitioning.FORWARD, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aChainThatReadsFromTheNetworkIsDeployedWithItsInputs() {
        ChainDescriptor tail = new ChainDescriptor("out", JOB, List.of(node("out", NodeKind.SINK)));
        ExecutionAttemptId task = new ExecutionAttemptId("run", "out", 0, 0);
        TaskDeployment.Input input = new TaskDeployment.Input(
                0,
                "src",
                List.of(new TaskDeployment.Channel(new ExecutionAttemptId("run", "src", 0, 0), 0, 0, "w2:6124")));
        RunContext run = RunContext.of("r", Instant.EPOCH);
        assertThat(new TaskDeployment(task, tail, 1, TaskSettings.defaults(), run, null, List.of(input)).inputs())
                .containsExactly(input);
        assertThatThrownBy(() -> new TaskDeployment(task, tail, 1, TaskSettings.defaults(), run))
                .hasMessageContaining("reads from the network and needs its inputs");
        ChainDescriptor full =
                new ChainDescriptor("src", JOB, List.of(node("src", NodeKind.SOURCE), node("out", NodeKind.SINK)));
        assertThatThrownBy(() -> new TaskDeployment(task, full, 1, TaskSettings.defaults(), run, null, List.of(input)))
                .hasMessageContaining("starts with a source and has no inputs");
        assertThatThrownBy(() -> new TaskDeployment.Input(0, "src", List.of())).hasMessageContaining("has no channels");
    }

    @Test
    void deploymentsAreForASubtaskWithinTheParallelism() {
        ChainDescriptor chain =
                new ChainDescriptor("src", JOB, List.of(node("src", NodeKind.SOURCE), node("out", NodeKind.SINK)));
        ExecutionAttemptId second = new ExecutionAttemptId("run", "src", 1, 0);

        assertThat(new TaskDeployment(second, chain, 2, TaskSettings.defaults(), RunContext.of("r", Instant.EPOCH))
                        .parallelism())
                .isEqualTo(2);
        assertThatThrownBy(() -> new TaskDeployment(
                        second, chain, 1, TaskSettings.defaults(), RunContext.of("r", Instant.EPOCH)))
                .hasMessageContaining("outside parallelism 1");
    }
}
