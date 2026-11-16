/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.engine.api.Bottleneck;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.RunAnalyzer;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The rate limit of a job from end to end, on two workers. */
class RateLimitTest {

    private LocalEngine engine;

    @BeforeEach
    void start() {
        engine = new LocalEngine(2);
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private JobResult run(String source, String env) throws Exception {
        String probe = Probe.create();
        JobResult result = engine.submit(TestConnectors.job(probe, source, null, "", 2, env), SubmitOptions.defaults())
                .result()
                .get(60, TimeUnit.SECONDS);
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        return result;
    }

    @Test
    void readersTogetherKeepToTheLimitOfTheJob() throws Exception {
        long started = System.nanoTime();
        JobResult limited = run("rows: 600, splits: 6", "limit: { rows-per-second: 400 }");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(limited.metrics().rowsWritten()).isEqualTo(600);
        // 600 rows at 400 per second: a second and a half, less what the first period lets through
        assertThat(ms).as("the run took %d ms", ms).isGreaterThanOrEqualTo(1_200);
        assertThat(limited.metrics().throttleWaitMs()).isPositive();
        assertThat(RunAnalyzer.analyze("j", JobStatus.SUCCESS, java.util.List.of(limited.metrics()))
                        .bottleneck()
                        .kind())
                .isEqualTo(Bottleneck.Kind.THROTTLE);

        JobResult free = run("rows: 600, splits: 6", "");
        assertThat(free.metrics().throttleWaitMs()).as("no limit, no waiting").isZero();
    }

    @Test
    void aByteLimitHoldsBackLargeRows() throws Exception {
        long started = System.nanoTime();
        // rows of about 2,000 bytes in memory at 200,000 bytes per second: about 100 rows per second
        JobResult limited = run("rows: 150, splits: 2, pad: 970", "limit: { bytes-per-second: 200000 }");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(limited.metrics().rowsWritten()).isEqualTo(150);
        assertThat(ms).as("the run took %d ms", ms).isGreaterThanOrEqualTo(1_000);
        assertThat(limited.metrics().throttleWaitMs()).isPositive();
    }

    @Test
    void aSourceThatLimitsItselfGetsItsQuota() throws Exception {
        String probe = Probe.create();
        JobResult result = engine.submit(
                        TestConnectors.job(
                                probe,
                                "rows: 100, splits: 2, own-limit: true",
                                null,
                                "",
                                2,
                                "limit: { rows-per-second: 300 }"),
                        SubmitOptions.defaults())
                .result()
                .get(60, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(JobStatus.SUCCESS);
        // two readers, each told its half; the engine does not hold them back
        assertThat(Probe.get(probe).events).contains("own limit 150");
        assertThat(result.metrics().throttleWaitMs()).isZero();
    }
}
