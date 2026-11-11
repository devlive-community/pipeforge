/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyzeTextTest {

    @Test
    void printsTheJobRowsRatesAndNodes() {
        JobMetrics metrics = new JobMetrics(
                12_400,
                1_000_000,
                999_000,
                1_000,
                0,
                0,
                0,
                new Rate(0, 80_645, 91_200),
                new Rate(0, 80_564, 91_000),
                1_200,
                300,
                4,
                0,
                List.of(
                        new NodeMetrics("src", "source", 1_000_000, 1_000_000, 0, 0, 0.21),
                        new NodeMetrics("map", "transform", 1_000_000, 999_000, 1_000, 0, 0.04),
                        new NodeMetrics("out", "sink", 999_000, 999_000, 0, 0, -1)));

        assertThat(AnalyzeText.format("orders-copy", JobStatus.SUCCESS, metrics))
                .isEqualTo(
                        """
                        Job orders-copy  SUCCESS 12.4s  parallelism 4
                        Rows   read 1,000,000  written 999,000  filtered 1,000  in-flight 0  failed 0   reconcile OK
                        Rate   read 0 r/s (avg 80,645, peak 91,200)   write 0 r/s (avg 80,564, peak 91,000)
                        Wait   reader 1.2s (target slow)  writer 300ms (source slow)

                        Node  kind       in rows    out rows   sel.  self%
                        src   source     1,000,000  1,000,000  -     21%
                        map   transform  1,000,000  999,000    1.00  4%
                        out   sink       999,000    999,000    -     -
                        """);
    }

    @Test
    void marksRunningJobsAndMismatches() {
        JobMetrics metrics =
                new JobMetrics(3_725_000, 10, 2, 0, 5, 0, 1, Rate.ZERO, Rate.ZERO, 0, 61_000, 2, 1, List.of());

        String text = AnalyzeText.format("j", JobStatus.RUNNING, metrics);
        assertThat(text)
                .startsWith("Job j  RUNNING 1h2m  parallelism 2  tasks running 1\n")
                .contains("expanded 5")
                .contains("reconcile MISMATCH")
                .contains("writer 1m1s");
        assertThat(AnalyzeText.count(-1)).isEqualTo("-");
    }
}
