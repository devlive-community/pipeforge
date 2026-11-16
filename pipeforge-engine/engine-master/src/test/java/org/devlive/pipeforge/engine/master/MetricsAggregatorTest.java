/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.rpc.OperatorSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.Test;

/**
 * Live metrics reconcile even when the counters of a task were read while it moved rows: the
 * reporter thread reads them one operator after the other, so a downstream counter can be ahead of
 * what the source counter showed a moment before.
 */
class MetricsAggregatorTest {

    static MetricsAggregator aggregator() {
        JobPlan plan = TestConnectors.job(Probe.create(), "rows: 100", "", "", 2);
        return new MetricsAggregator(org.devlive.pipeforge.engine.master.graph.JobGraph.of(plan), 0);
    }

    static TaskMetricsSnapshot task(long sourceOut, long filtered, long expanded, long sinkIn, long sinkOut) {
        return new TaskMetricsSnapshot(
                List.of(
                        new OperatorSnapshot("src", sourceOut, sourceOut, 0, 0, 0),
                        new OperatorSnapshot("up", sourceOut, sourceOut - filtered + expanded, filtered, expanded, 0),
                        new OperatorSnapshot("out", sinkIn, sinkOut, 0, 0, 0)),
                1_000_000,
                0,
                0,
                0);
    }

    @Test
    void aLiveSnapshotReconcilesWhenDownstreamCountersAreAhead() {
        MetricsAggregator metrics = aggregator();
        // read in one order, the counters of task 0 would say 10 - 1 + 3 = 12 rows reached the sink;
        // the sink counter was read later and has seen 2 more
        metrics.update("src", 0, task(10, 1, 3, 14, 8));
        metrics.update("src", 1, task(5, 0, 0, 5, 5));

        JobMetrics live = metrics.sample(1_000_000_000L, 2, MetricsAggregator.Phase.RUNNING);

        assertThat(live.rowsRead()).isEqualTo(15);
        assertThat(live.rowsWritten()).isEqualTo(13);
        assertThat(live.rowsFiltered()).isEqualTo(1);
        assertThat(live.rowsExpanded()).isEqualTo(3);
        // read + expanded - filtered - accepted
        assertThat(live.rowsInFlight()).isEqualTo(4);
        assertThat(live.reconciled()).isTrue();
    }

    @Test
    void theEndOfARunStillCountsWhatDidNotArrive() {
        MetricsAggregator metrics = aggregator();
        metrics.update("src", 0, task(10, 0, 0, 10, 7));
        metrics.update("src", 1, task(10, 0, 0, 10, 10));

        JobMetrics stopped = metrics.sample(2_000_000_000L, 0, MetricsAggregator.Phase.STOPPED);

        assertThat(stopped.rowsInFlight()).isZero();
        assertThat(stopped.rowsFailed()).isEqualTo(3);
        assertThat(stopped.reconciled()).isTrue();
    }

    @Test
    void sumsTheExchangeTheQueuesAndTheSplitsOfAPipeline() {
        JobPlan plan = TestConnectors.plan(
                """
                version: 1
                name: rescale
                source:
                  - { id: src, type: numbers, probe: x, parallelism: 2 }
                sink:
                  - { id: out, type: collect, probe: x, parallelism: 3 }
                """);
        MetricsAggregator metrics =
                new MetricsAggregator(org.devlive.pipeforge.engine.master.graph.JobGraph.of(plan), 0);
        assertThat(metrics.sample(1, 0, MetricsAggregator.Phase.RUNNING).backpressuredRatio())
                .as("nothing ran yet")
                .isEqualTo(-1);
        for (int i = 0; i < 2; i++) {
            metrics.update(
                    "src",
                    i,
                    new TaskMetricsSnapshot(
                            List.of(new OperatorSnapshot("src", 100, 100, 0, 0, 0)),
                            1_000_000_000L,
                            0,
                            0,
                            0,
                            250_000_000L,
                            4_000,
                            0,
                            0,
                            0));
        }
        for (int i = 0; i < 3; i++) {
            metrics.update(
                    "out",
                    i,
                    new TaskMetricsSnapshot(
                            List.of(new OperatorSnapshot("out", 60, 60, 0, 0, 0)),
                            1_000_000_000L,
                            0,
                            5_000_000L,
                            0,
                            0,
                            0,
                            2_000,
                            1,
                            4));
        }
        metrics.splits(6, 2);
        JobMetrics live = metrics.sample(2_000_000_000L, 5, MetricsAggregator.Phase.RUNNING);

        assertThat(live.exchangedBytes()).isEqualTo(8_000);
        assertThat(live.backpressuredMs()).isEqualTo(500);
        assertThat(live.backpressuredRatio()).isEqualTo(0.1);
        assertThat(live.readerWaitMs()).as("waiting for the write queue only").isEqualTo(15);
        assertThat(live.queueDepth()).isEqualTo(3);
        assertThat(live.queueCapacity()).isEqualTo(12);
        assertThat(live.splitsAssigned()).isEqualTo(6);
        assertThat(live.splitsFinished()).isEqualTo(2);
        assertThat(live.node("src")).hasValueSatisfying(n -> {
            assertThat(n.bytesOut()).isEqualTo(8_000);
            assertThat(n.bytesIn()).isZero();
        });
        assertThat(live.node("out")).hasValueSatisfying(n -> {
            assertThat(n.bytesIn()).isEqualTo(6_000);
            assertThat(n.bytesOut()).isZero();
        });
        assertThat(live.rowsRead()).isEqualTo(200);
        assertThat(live.rowsWritten()).isEqualTo(180);
        assertThat(live.rowsInFlight()).isEqualTo(20);
        assertThat(live.reconciled()).isTrue();
    }
}
