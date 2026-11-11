/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.engine.api.AnalyzeText;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.NodeMetrics;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalMetricsTest {

    private LocalEngine engine;

    @BeforeEach
    void start() {
        engine = new LocalEngine();
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private JobResult run(String source, String transform, String sink, int parallelism) throws Exception {
        return run(source, transform, sink, parallelism, SubmitOptions.defaults());
    }

    private JobResult run(String source, String transform, String sink, int parallelism, SubmitOptions options)
            throws Exception {
        return engine.submit(TestConnectors.job(Probe.create(), source, transform, sink, parallelism), options)
                .result()
                .get(30, TimeUnit.SECONDS);
    }

    @Test
    void reconcilesReadFilteredAndWrittenRows() throws Exception {
        JobResult result = run("rows: 1000, splits: 5", "drop-odd: true", "", 2);

        assertThat(result.status()).isEqualTo(JobStatus.SUCCESS);
        JobMetrics metrics = result.metrics();
        assertThat(metrics.rowsRead()).isEqualTo(1000);
        assertThat(metrics.rowsFiltered()).isEqualTo(500);
        assertThat(metrics.rowsWritten()).isEqualTo(500);
        assertThat(metrics.rowsInFlight()).isZero();
        assertThat(metrics.rowsFailed()).isZero();
        assertThat(metrics.reconciled()).isTrue();
        assertThat(metrics.parallelism()).isEqualTo(2);
        assertThat(metrics.tasksRunning()).isZero();
        assertThat(metrics.nodes()).extracting(NodeMetrics::node).containsExactly("src", "up", "out");
        assertThat(metrics.node("src").orElseThrow().rowsOut()).isEqualTo(1000);
        NodeMetrics up = metrics.node("up").orElseThrow();
        assertThat(up.kind()).isEqualTo("transform");
        assertThat(up.rowsIn()).isEqualTo(1000);
        assertThat(up.rowsOut()).isEqualTo(500);
        assertThat(up.selectivity()).isEqualTo(0.5);
        assertThat(metrics.node("out").orElseThrow().rowsIn()).isEqualTo(500);
        assertThat(metrics.nodes())
                .allSatisfy(n -> assertThat(n.selfTimeRatio()).isBetween(0.0, 1.0));
        assertThat(metrics.readRowsPerSecond().average()).isPositive();
        assertThat(metrics.readRowsPerSecond().peak())
                .isGreaterThanOrEqualTo(metrics.readRowsPerSecond().average());
        assertThat(metrics.readRowsPerSecond().current()).isZero();
        assertThat(metrics.writeRowsPerSecond().average()).isPositive();
        assertThat(AnalyzeText.format("test-job", result.status(), metrics)).contains("reconcile OK");
    }

    @Test
    void countsRowsThatTransformsExpand() throws Exception {
        JobMetrics metrics =
                run("rows: 300, splits: 3", "duplicate: true", "", 1).metrics();

        assertThat(metrics.rowsExpanded()).isEqualTo(300);
        assertThat(metrics.rowsWritten()).isEqualTo(600);
        assertThat(metrics.reconciled()).isTrue();
    }

    @Test
    void countsTwoPhaseRowsAsWrittenOnlyOnceCommitted() throws Exception {
        JobMetrics committed =
                run("rows: 40, splits: 4", null, "commit: two-phase", 2).metrics();
        assertThat(committed.rowsWritten()).isEqualTo(40);
        assertThat(committed.reconciled()).isTrue();

        JobResult failed = run("rows: 40, splits: 4", null, "commit: fail", 2);
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.metrics().rowsWritten()).isZero();
        assertThat(failed.metrics().rowsFailed()).isEqualTo(40);
        assertThat(failed.metrics().node("out").orElseThrow().rowsOut()).isZero();
        assertThat(failed.metrics().reconciled()).isTrue();
    }

    @Test
    void countsRowsThatNeverReachedTheTargetOfAFailedJob() throws Exception {
        JobResult result = run("rows: 1000, splits: 4", "fail-at: 100", "", 1);

        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        JobMetrics metrics = result.metrics();
        assertThat(metrics.rowsRead()).isGreaterThanOrEqualTo(101);
        assertThat(metrics.rowsFailed()).isEqualTo(metrics.rowsRead() - metrics.rowsWritten());
        assertThat(metrics.rowsInFlight()).isZero();
        assertThat(metrics.reconciled()).isTrue();
    }

    @Test
    void publishesMetricsWhileTheJobRunsAndReconcilesACancelledJob() throws Exception {
        SubmitOptions often = SubmitOptions.defaults()
                .withMetricsInterval(Duration.ofMillis(20))
                .withWriteBatchSize(1)
                .withWriteQueueCapacity(1);
        JobHandle handle = engine.submit(
                TestConnectors.job(Probe.create(), "rows: 1000000, splits: 2", null, "slow-ms: 1", 1), often);

        JobMetrics live = JobMetrics.EMPTY;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (live.rowsWritten() < 20 && System.nanoTime() < deadline) {
            Thread.sleep(20);
            live = handle.metrics();
        }
        assertThat(handle.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(live.rowsWritten()).isGreaterThanOrEqualTo(20);
        assertThat(live.tasksRunning()).isEqualTo(1);
        assertThat(live.reconciled()).isTrue();
        assertThat(live.elapsedMs()).isPositive();
        assertThat(live.writeRowsPerSecond().current()).isPositive();

        handle.cancel();
        JobResult result = handle.result().get(30, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
        JobMetrics end = result.metrics();
        assertThat(end.rowsInFlight()).isZero();
        assertThat(end.rowsFailed()).isEqualTo(end.rowsRead() - end.rowsWritten());
        assertThat(end.reconciled()).isTrue();
        assertThat(end.readerWaitMs())
                .as("the slow sink blocks the task thread")
                .isPositive();
        assertThat(handle.metrics()).isEqualTo(end);
    }

    @Test
    void measuresTheSelfTimeOfASlowSink() throws Exception {
        JobMetrics metrics = run("rows: 200, splits: 2", "", "slow-ms: 2", 1).metrics();

        NodeMetrics out = metrics.node("out").orElseThrow();
        NodeMetrics src = metrics.node("src").orElseThrow();
        assertThat(out.selfTimeRatio()).isGreaterThan(0.5);
        assertThat(out.selfTimeRatio()).isGreaterThan(src.selfTimeRatio());
    }
}
