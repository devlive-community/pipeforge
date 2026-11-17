/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

/** The report format and the checks of the benchmark, without a cluster. */
class BenchmarkReportTest {

    static ArrayNode timeline(double[] rates, int[] splits) {
        ArrayNode timeline = Cluster.JSON.createArrayNode();
        for (int i = 0; i < rates.length; i++) {
            timeline.addObject()
                    .put("seconds", i * 5.0)
                    .put("rowsRead", i * 1000L)
                    .put("rowsWritten", i * 900L)
                    .put("readRowsPerSecond", rates[i])
                    .put("splitsFinished", splits[i])
                    .put("splitsTotal", 32);
        }
        return timeline;
    }

    @Test
    void ratesAgreeWithTheThroughputWithinThirtyPercent() {
        ArrayNode steady = timeline(new double[] {10, 1000, 1100, 900, 1050, 20}, new int[] {0, 4, 8, 16, 24, 32});
        assertThat(AcceptanceBenchmark.rateAgreement(steady, 1000).passed()).isTrue();
        assertThat(AcceptanceBenchmark.rateAgreement(steady, 2000).passed()).isFalse();
        assertThat(AcceptanceBenchmark.rateAgreement(timeline(new double[] {1, 2}, new int[] {0, 1}), 1000))
                .satisfies(check -> {
                    assertThat(check.informational()).isTrue();
                    assertThat(check.detail()).startsWith("too short to compare");
                });
    }

    /** Samples at 0%, 50% and 100% of 32,000 rows in 32 splits read by 4 readers. */
    static ArrayNode progress(int[] finished, int[] totals) {
        ArrayNode timeline = Cluster.JSON.createArrayNode();
        long[] read = {0, 16_000, 32_000};
        for (int i = 0; i < finished.length; i++) {
            timeline.addObject()
                    .put("seconds", i * 5.0)
                    .put("rowsRead", read[i])
                    .put("splitsFinished", finished[i])
                    .put("splitsTotal", totals[i]);
        }
        return timeline;
    }

    @Test
    void splitProgressMustFollowTheRows() {
        int[] all = {32, 32, 32};
        assertThat(AcceptanceBenchmark.splitProgress(progress(new int[] {0, 14, 32}, all), 32_000, 4))
                .satisfies(check -> {
                    assertThat(check.passed()).as(check.detail()).isTrue();
                    assertThat(check.detail()).isEqualTo("3 sample(s), 32 split(s) from the first");
                });
        // half the rows read but no split counted: progress only at the end of the readers
        assertThat(AcceptanceBenchmark.splitProgress(progress(new int[] {0, 0, 32}, all), 32_000, 4))
                .satisfies(check -> {
                    assertThat(check.passed()).isFalse();
                    assertThat(check.detail()).isEqualTo("5 s: 0 of 32 splits read with 50% of the rows");
                });
        // the total grows as splits are handed out
        assertThat(AcceptanceBenchmark.splitProgress(progress(new int[] {0, 14, 32}, new int[] {8, 28, 32}), 32_000, 4)
                        .detail())
                .contains("5 s: total 28, first 8");
        // fewer splits read than before
        assertThat(AcceptanceBenchmark.splitProgress(progress(new int[] {0, 16, 15}, all), 32_000, 4)
                        .detail())
                .contains("10 s: 15 read after 16");
    }

    @Test
    void theMarkdownReportShowsResultChecksTimelineAndEnvironment() throws Exception {
        JsonNode report = Cluster.JSON.readTree(
                """
                {"passed": true, "at": "2026-11-17T12:00:00Z",
                 "environment": {"commit": "abc1234", "os": "Linux 6.8", "cpus": 16, "memoryBytes": 68719476736,
                   "java": "OpenJDK 21", "mysql": "8.4.3", "postgresql": "16.6", "databases": "external"},
                 "settings": {"rows": 100000000, "columns": 20, "workers": 3, "slotsPerWorker": 16,
                   "parallelism": 8, "splits": 32},
                 "result": {"runSeconds": 301.5, "rowsPerSecond": 331674.9, "rowsPerSecondPerSlot": 41459.4,
                   "datasetReused": true, "datasetLoadSeconds": 1.2,
                   "metrics": {"rowsRead": 100000000, "rowsWritten": 100000000, "splitsFinished": 32,
                     "splitsTotal": 32},
                   "bottleneck": {"kind": "TARGET", "node": "warehouse",
                     "advice": [{"code": "sink-parallelism", "text": "Raise the parallelism of warehouse"}]}},
                 "timeline": [{"seconds": 5, "rowsRead": 1500000, "rowsWritten": 1490000,
                   "readRowsPerSecond": 300000.4, "splitsFinished": 1, "splitsTotal": 32}],
                 "checks": [
                   {"name": "every split was read", "passed": true, "informational": false, "detail": "32 of 32"},
                   {"name": "throughput", "passed": false, "informational": true, "detail": "a | b"}]}
                """);
        String markdown = BenchmarkReport.markdown(report);
        assertThat(markdown)
                .startsWith(
                        "# M1 benchmark: MySQL → PostgreSQL\n\n**Passed** at 2026-11-17T12:00:00Z, commit `abc1234`.")
                .contains("| Rows | 100,000,000 |")
                .contains("| Run | 5 min 02 s |")
                .contains("| Throughput | 331,675 rows/s |")
                .contains("| Per slot | 41,459 rows/s at parallelism 8 |")
                .contains("| Bottleneck | TARGET at `warehouse` |")
                .contains("| Dataset | reused |")
                .contains("| every split was read | passed | 32 of 32 |")
                .contains("| throughput | not met (informational) | a \\| b |")
                .contains("- Raise the parallelism of warehouse")
                .contains("| 5 | 1,500,000 | 1,490,000 | 300,000 | 1 of 32 |")
                .contains("| Workers | 3 × 16 slots |")
                .contains("| Host | Linux 6.8, 16 CPUs, 64 GiB |");
    }
}
