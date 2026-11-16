/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;

import org.devlive.pipeforge.engine.api.JobMetrics;

/** Checks of the metrics of a run that exchanged rows between two vertices. */
final class JobMetricsCheck {

    private JobMetricsCheck() {}

    static void exchange(JobMetrics metrics, String producer, String consumer, int splits) {
        assertThat(metrics.exchangedBytes()).isPositive();
        assertThat(metrics.node(producer))
                .hasValueSatisfying(n -> assertThat(n.bytesOut()).isEqualTo(metrics.exchangedBytes()));
        assertThat(metrics.node(consumer)).hasValueSatisfying(n -> {
            assertThat(n.bytesIn()).isEqualTo(metrics.exchangedBytes());
            assertThat(n.bytesOut()).isZero();
        });
        assertThat(metrics.splitsAssigned()).isEqualTo(splits);
        assertThat(metrics.splitsFinished()).isEqualTo(splits);
        assertThat(metrics.backpressuredMs()).isNotNegative();
        assertThat(metrics.backpressuredRatio()).isBetween(0.0, 1.0);
        assertThat(metrics.queueCapacity())
                .as("3 sinks with a write queue of 4")
                .isEqualTo(12);
        assertThat(metrics.queueDepth()).isZero();
        assertThat(metrics.reconciled()).isTrue();
    }
}
