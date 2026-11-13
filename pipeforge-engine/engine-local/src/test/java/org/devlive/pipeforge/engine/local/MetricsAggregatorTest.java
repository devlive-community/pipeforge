/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
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
        ChainDescriptor chain = new ChainDescriptor(
                plan.nodes().get(0).id(),
                new JobContext(plan.name(), plan.parallelism(), plan.timeZone()),
                plan.nodes());
        return new MetricsAggregator(chain, 2, 0);
    }

    static TaskMetricsSnapshot task(long sourceOut, long filtered, long expanded, long sinkIn, long sinkOut) {
        return new TaskMetricsSnapshot(
                List.of(
                        new OperatorSnapshot("src", 0, sourceOut, 0, 0, 0),
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
        metrics.update(0, task(10, 1, 3, 14, 8));
        metrics.update(1, task(5, 0, 0, 5, 5));

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
        metrics.update(0, task(10, 0, 0, 10, 7));
        metrics.update(1, task(10, 0, 0, 10, 10));

        JobMetrics stopped = metrics.sample(2_000_000_000L, 0, MetricsAggregator.Phase.STOPPED);

        assertThat(stopped.rowsInFlight()).isZero();
        assertThat(stopped.rowsFailed()).isEqualTo(3);
        assertThat(stopped.reconciled()).isTrue();
    }
}
