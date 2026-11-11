/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void rejectsChainsThatAreNotSourceTransformsSink() {
        PlannedNode src = node("src", NodeKind.SOURCE);
        PlannedNode out = node("out", NodeKind.SINK);

        assertThatThrownBy(() -> new ChainDescriptor("src", JOB, List.of(src)))
                .hasMessageContaining("at least a source and a sink");
        assertThatThrownBy(() -> new ChainDescriptor("out", JOB, List.of(out, out)))
                .hasMessageContaining("starts with a source");
        assertThatThrownBy(() -> new ChainDescriptor("src", JOB, List.of(src, src)))
                .hasMessageContaining("ends with a sink");
        assertThatThrownBy(() -> new ChainDescriptor("src", JOB, List.of(src, out, out)))
                .hasMessageContaining("Only transforms");
    }

    @Test
    void deploymentsAreForASubtaskWithinTheParallelism() {
        ChainDescriptor chain =
                new ChainDescriptor("src", JOB, List.of(node("src", NodeKind.SOURCE), node("out", NodeKind.SINK)));
        ExecutionAttemptId second = new ExecutionAttemptId("run", "src", 1, 0);

        assertThat(new TaskDeployment(second, chain, 2, TaskSettings.defaults()).parallelism())
                .isEqualTo(2);
        assertThatThrownBy(() -> new TaskDeployment(second, chain, 1, TaskSettings.defaults()))
                .hasMessageContaining("outside parallelism 1");
    }
}
