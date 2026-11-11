/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.explain.ExplainDocument;
import org.junit.jupiter.api.Test;

class EngineApiTest {

    @Test
    void jobIdsAreNonBlankUuids() {
        JobId id = JobId.random();

        assertThat(id.value()).hasSize(36);
        assertThat(id.toString()).isEqualTo(id.value());
        assertThat(JobId.random()).isNotEqualTo(id);
        assertThatThrownBy(() -> new JobId(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlySuccessFailedAndCancelledAreTerminal() {
        assertThat(List.of(JobStatus.values()).stream().filter(JobStatus::isTerminal))
                .containsExactly(JobStatus.SUCCESS, JobStatus.FAILED, JobStatus.CANCELLED);
    }

    @Test
    void resultsHaveAnErrorExactlyWhenTheJobFailed() {
        JobId id = JobId.random();
        PipeForgeException error = new PipeForgeException(ErrorCode.INTERNAL, Map.of("detail", "boom"));

        assertThat(new JobResult(id, JobStatus.FAILED, error, JobMetrics.EMPTY).error())
                .isSameAs(error);
        assertThat(new JobResult(id, JobStatus.SUCCESS, null, JobMetrics.EMPTY).metrics())
                .isSameAs(JobMetrics.EMPTY);
        assertThatThrownBy(() -> new JobResult(id, JobStatus.SUCCESS, error, JobMetrics.EMPTY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobResult(id, JobStatus.FAILED, null, JobMetrics.EMPTY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobResult(id, JobStatus.RUNNING, null, JobMetrics.EMPTY))
                .hasMessageContaining("final status");
    }

    @Test
    void submitOptionsHaveDefaultsAndAreChecked() {
        SubmitOptions defaults = SubmitOptions.defaults();

        assertThat(defaults.asyncWriteBuffer()).isTrue();
        assertThat(defaults.writeBatchSize()).isEqualTo(1024);
        assertThat(defaults.writeQueueCapacity()).isEqualTo(4);
        assertThat(defaults.cancelTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.metricsInterval()).isEqualTo(Duration.ofSeconds(1));
        SubmitOptions changed = defaults.withAsyncWriteBuffer(false)
                .withWriteBatchSize(10)
                .withWriteQueueCapacity(2)
                .withCancelTimeout(Duration.ofSeconds(1))
                .withMetricsInterval(Duration.ofMillis(50));
        assertThat(changed).isEqualTo(new SubmitOptions(false, 10, 2, Duration.ofSeconds(1), Duration.ofMillis(50)));
        assertThatThrownBy(() -> defaults.withMetricsInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withWriteBatchSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withWriteQueueCapacity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withCancelTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explainResultsNeedAPlanAndText() {
        assertThatThrownBy(() -> new ExplainResult(null, "x")).isInstanceOf(NullPointerException.class);
        ExplainDocument document = null;
        assertThatThrownBy(() -> new ExplainResult(document, null)).isInstanceOf(NullPointerException.class);
    }
}
