/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RunAnalyzerTest {

    /** Three nodes; the source sends {@code bytes} to the other tasks. */
    private static List<NodeMetrics> nodes(long rows, double sourceBusy, double sinkBusy, long bytes) {
        return List.of(
                new NodeMetrics("src", "source", rows, rows, 0, 0, sourceBusy, 0, bytes),
                new NodeMetrics("map", "transform", rows, rows, 0, 0, 0.05, bytes, 0),
                new NodeMetrics("out", "sink", rows, rows, 0, 0, sinkBusy, 0, 0));
    }

    /** A sample: times in milliseconds, waits summed over the tasks. */
    private static JobMetrics sample(
            long elapsed,
            long running,
            long readerWait,
            long writerWait,
            long backpressured,
            long bytes,
            List<NodeMetrics> nodes) {
        long rows = nodes.isEmpty() ? 0 : nodes.get(0).rowsOut();
        return new JobMetrics(
                elapsed,
                rows,
                rows,
                0,
                0,
                0,
                0,
                Rate.ZERO,
                Rate.ZERO,
                readerWait,
                writerWait,
                4,
                4,
                nodes,
                bytes,
                backpressured,
                running == 0 ? -1 : (double) backpressured / running,
                0,
                0,
                0,
                0,
                running,
                0);
    }

    private record Case(
            String name, JobMetrics metrics, Bottleneck.Kind kind, @Nullable String node, String firstEvidence) {}

    @Test
    void concludesFromTheSharesOfTheWaits() {
        List<Case> cases = List.of(
                new Case(
                        "readers wait for the write queue",
                        sample(10_000, 40_000, 28_000, 0, 0, 0, nodes(1000, 0.1, 0.9, 0)),
                        Bottleneck.Kind.TARGET,
                        "out",
                        "readerWaitShare"),
                new Case(
                        "tasks wait for buffers of a busy sink",
                        sample(10_000, 40_000, 0, 0, 24_000, 9_000, nodes(1000, 0.1, 0.8, 9_000)),
                        Bottleneck.Kind.TARGET,
                        "out",
                        "backpressuredShare"),
                new Case(
                        "tasks wait for buffers of an idle sink",
                        sample(10_000, 40_000, 0, 0, 24_000, 9_000, nodes(1000, 0.1, 0.2, 9_000)),
                        Bottleneck.Kind.NETWORK,
                        "src",
                        "backpressuredShare"),
                new Case(
                        "no exchange, so waiting for buffers is no network limit",
                        sample(10_000, 40_000, 0, 0, 24_000, 0, nodes(1000, 0.1, 0.2, 0)),
                        Bottleneck.Kind.NONE,
                        null,
                        "readerWaitShare"),
                new Case(
                        "a sink in the chain of its source is just busy",
                        sample(10_000, 40_000, 1_000, 2_000, 0, 0, nodes(1000, 0.3, 0.8, 0)),
                        Bottleneck.Kind.TARGET,
                        "out",
                        "selfTimeRatio"),
                new Case(
                        "write threads wait for rows",
                        sample(10_000, 40_000, 0, 32_000, 0, 0, nodes(1000, 0.3, 0.1, 0)),
                        Bottleneck.Kind.SOURCE,
                        "src",
                        "writerWaitShare"),
                new Case(
                        "the busy source keeps everyone waiting a little",
                        sample(10_000, 40_000, 2_000, 0, 1_000, 0, nodes(1000, 0.9, 0.1, 0)),
                        Bottleneck.Kind.SOURCE,
                        "src",
                        "readerWaitShare"),
                new Case(
                        "nothing waits for long",
                        sample(10_000, 40_000, 4_000, 4_000, 4_000, 0, nodes(1000, 0.3, 0.3, 0)),
                        Bottleneck.Kind.NONE,
                        null,
                        "readerWaitShare"),
                new Case("no task ran yet", sample(500, 0, 0, 0, 0, 0, List.of()), Bottleneck.Kind.UNKNOWN, null, ""));
        for (Case c : cases) {
            Bottleneck bottleneck = RunAnalyzer.analyze("j", JobStatus.SUCCESS, List.of(c.metrics()))
                    .bottleneck();
            assertThat(bottleneck.kind()).as(c.name()).isEqualTo(c.kind());
            assertThat(bottleneck.node()).as(c.name()).isEqualTo(c.node());
            if (!c.firstEvidence().isEmpty()) {
                assertThat(bottleneck.evidence().get(0).metric()).as(c.name()).isEqualTo(c.firstEvidence());
            }
            assertThat(bottleneck.advice().isEmpty())
                    .as(c.name() + ": advice only with a limit")
                    .isEqualTo(c.kind() == Bottleneck.Kind.NONE || c.kind() == Bottleneck.Kind.UNKNOWN);
        }
    }

    /** {@code m} with {@code throttleWaitMs}. */
    private static JobMetrics throttled(JobMetrics m, long throttleWaitMs) {
        return new JobMetrics(
                m.elapsedMs(),
                m.rowsRead(),
                m.rowsWritten(),
                m.rowsFiltered(),
                m.rowsExpanded(),
                m.rowsFailed(),
                m.rowsInFlight(),
                m.readRowsPerSecond(),
                m.writeRowsPerSecond(),
                m.readerWaitMs(),
                m.writerWaitMs(),
                m.parallelism(),
                m.tasksRunning(),
                m.nodes(),
                m.exchangedBytes(),
                m.backpressuredMs(),
                m.backpressuredRatio(),
                m.queueDepth(),
                m.queueCapacity(),
                m.splitsAssigned(),
                m.splitsFinished(),
                m.runningMs(),
                m.memoryBytes(),
                m.rowsDirty(),
                m.dirtySamples(),
                throttleWaitMs);
    }

    @Test
    void aRateLimitThatHoldsTheReadersBackComesFirst() {
        // readers also wait for the write queue: the limit is still what decides the speed
        JobMetrics limited = throttled(sample(10_000, 40_000, 28_000, 0, 0, 0, nodes(1000, 0.1, 0.9, 0)), 12_000);
        Bottleneck bottleneck =
                RunAnalyzer.analyze("j", JobStatus.SUCCESS, List.of(limited)).bottleneck();
        assertThat(bottleneck.kind()).isEqualTo(Bottleneck.Kind.THROTTLE);
        assertThat(bottleneck.node()).isEqualTo("src");
        assertThat(bottleneck.evidence()).containsExactly(new Bottleneck.Evidence("throttleWaitShare", null, 0.3));
        assertThat(bottleneck.advice()).extracting(Bottleneck.Advice::code).containsExactly("rate-limit");
        assertThat(AnalyzeText.format("j", JobStatus.SUCCESS, limited))
                .contains("throttled 12.0s (rate limit)")
                .contains("Bottleneck  THROTTLE at src: readers waited 30% of their time for the rate limit");

        JobMetrics barely = throttled(sample(10_000, 40_000, 28_000, 0, 0, 0, nodes(1000, 0.1, 0.9, 0)), 8_000);
        assertThat(RunAnalyzer.analyze("j", JobStatus.SUCCESS, List.of(barely))
                        .bottleneck()
                        .kind())
                .as("a fifth is not more than a fifth")
                .isEqualTo(Bottleneck.Kind.TARGET);
    }

    @Test
    void theEvidenceAndAdviceNameTheValuesAndTheNode() {
        Bottleneck target = RunAnalyzer.analyze(
                        "j", JobStatus.SUCCESS, List.of(sample(10_000, 40_000, 28_000, 0, 0, 0, nodes(1, 0.1, 0.9, 0))))
                .bottleneck();
        assertThat(target.evidence())
                .containsExactly(
                        new Bottleneck.Evidence("readerWaitShare", null, 0.7),
                        new Bottleneck.Evidence("selfTimeRatio", "out", 0.9));
        assertThat(target.advice())
                .extracting(Bottleneck.Advice::code)
                .containsExactly("sink-parallelism", "check-target");
        assertThat(target.advice().get(0).text()).contains("of out (now 4)");
    }

    @Test
    void aRunningJobIsJudgedOnTheLastTenSecondsAndAnEndedOneOnTheWholeRun() {
        // 20 s in which readers wait for the target, then 10 s in which the writers wait for rows
        List<JobMetrics> samples = List.of(
                sample(10_000, 40_000, 36_000, 0, 0, 0, nodes(1_000, 0.2, 0.8, 0)),
                sample(20_000, 80_000, 72_000, 0, 0, 0, nodes(2_000, 0.2, 0.8, 0)),
                sample(25_000, 100_000, 72_000, 18_000, 0, 0, nodes(2_500, 0.2, 0.8, 0)),
                sample(30_000, 120_000, 72_000, 36_000, 0, 0, nodes(5_000, 0.2, 0.8, 0)));

        RunAnalysis running = RunAnalyzer.analyze("j", JobStatus.RUNNING, samples);
        assertThat(running.windowFromMs()).isEqualTo(20_000);
        assertThat(running.windowToMs()).isEqualTo(30_000);
        assertThat(running.bottleneck().kind()).isEqualTo(Bottleneck.Kind.SOURCE);
        assertThat(running.bottleneck().evidence().get(0).value()).isEqualTo(0.9);
        assertThat(running.rates())
                .contains(new RunAnalysis.NodeRate("out", 300, 300), new RunAnalysis.NodeRate("src", 300, 300));

        RunAnalysis ended = RunAnalyzer.analyze("j", JobStatus.SUCCESS, samples);
        assertThat(ended.windowFromMs()).isZero();
        assertThat(ended.bottleneck().kind()).isEqualTo(Bottleneck.Kind.TARGET);
        assertThat(ended.rates()).contains(new RunAnalysis.NodeRate("out", 5_000 / 30.0, 5_000 / 30.0));

        RunAnalysis young = RunAnalyzer.analyze("j", JobStatus.RUNNING, samples.subList(0, 1));
        assertThat(young.windowFromMs())
                .as("younger than a window: from the start")
                .isZero();
    }

    @Test
    void theJsonFormReadsBack() {
        RunAnalysis analysis = RunAnalyzer.analyze(
                "orders", JobStatus.RUNNING, List.of(sample(10_000, 40_000, 28_000, 0, 0, 0, nodes(10, 0.1, 0.9, 0))));
        String json = analysis.json();
        assertThat(json)
                .startsWith("{\"schemaVersion\":\"1\",\"job\":\"orders\",\"status\":\"RUNNING\"")
                .contains("\"bottleneck\":{\"kind\":\"TARGET\",\"node\":\"out\"")
                .contains("\"code\":\"sink-parallelism\"");
        assertThat(RunAnalysis.fromJson(json)).isEqualTo(analysis);
        assertThat(analysis.text()).contains("Bottleneck  TARGET at out: readers waited 70% of their time");
    }
}
