/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** When a failed attempt is followed by another, when, and from where (design 34 section 3). */
class RunRetriesTest {

    static final Instant SUBMITTED = Instant.parse("2026-11-23T08:00:00Z");

    static Runs.Run run(int attempt) {
        return new Runs.Run(
                "r1",
                "job",
                RunState.RUNNING,
                null,
                SUBMITTED,
                SUBMITTED,
                null,
                SUBMITTED,
                null,
                null,
                null,
                attempt,
                null);
    }

    static RunRetries.Resubmission submission(String delivery, int attempts, String resume, Long withinMs) {
        return new RunRetries.Resubmission(
                Map.of(),
                SUBMITTED.toEpochMilli(),
                Map.of(),
                delivery,
                new RunRetries.Retry(
                        attempts,
                        Duration.ofMinutes(1).toMillis(),
                        Duration.ofMinutes(5).toMillis(),
                        2,
                        List.of("CONN", "RES", "CLUS", "TRANSIENT"),
                        resume,
                        withinMs));
    }

    @Test
    void transientFailuresAreRetriedUntilTheAttemptsAreUsedUp() {
        var exactly = submission("exactly-once", 2, "auto", null);
        Instant now = SUBMITTED.plusSeconds(60);

        var first = RunRetries.decide(run(1), "PF-CONN-0003", exactly, true, now);
        assertThat(first.retry()).isTrue();
        assertThat(first.fromCheckpoint()).isTrue();
        assertThat(first.at()).isEqualTo(now.plus(Duration.ofMinutes(1)));
        assertThat(first.reason()).contains("retry 1 of 2", "from the last checkpoint");

        var second = RunRetries.decide(run(2), "PF-CONN-0003", exactly, false, now);
        assertThat(second.at()).as("the backoff doubles").isEqualTo(now.plus(Duration.ofMinutes(2)));
        assertThat(second.fromCheckpoint())
                .as("without a checkpoint, from the start")
                .isFalse();

        var third = RunRetries.decide(run(3), "PF-CONN-0003", exactly, true, now);
        assertThat(third.retry()).isFalse();
        assertThat(third.reason()).contains("no retries left");
        assertThat(RunRetries.decide(run(1), "PF-CONN-0003", submission("exactly-once", 0, "auto", null), true, now)
                        .reason())
                .contains("env.retry is off");
    }

    @Test
    void onlyTheErrorsOfEnvRetryOnAreRetried() {
        var exactly = submission("exactly-once", 3, "auto", null);
        Instant now = SUBMITTED.plusSeconds(1);
        assertThat(RunRetries.decide(run(1), "PF-CLUS-0001", exactly, false, now)
                        .retry())
                .as("a lost worker: category CLUS")
                .isTrue();
        assertThat(RunRetries.decide(run(1), "PF-CONF-0001", exactly, false, now)
                        .retry())
                .as("a configuration error")
                .isFalse();
        assertThat(RunRetries.decide(run(1), "PF-DATA-0010", exactly, false, now)
                        .retry())
                .as("too many bad rows")
                .isFalse();
        assertThat(RunRetries.retried("PF-SINK-0001", List.of("PF-SINK-0001")))
                .as("by code")
                .isTrue();
        assertThat(RunRetries.retried("PF-SINK-0001", List.of("TRANSIENT")))
                .as("sink failures are fatal unless a plugin says otherwise")
                .isFalse();
        assertThat(RunRetries.retried("PF-CKPT-0007", List.of("CKPT"))).isTrue();
    }

    @Test
    void autoRetriesOnlyWhereNoRowIsWrittenTwiceAndWithinLimitsTheTime() {
        Instant now = SUBMITTED.plus(Duration.ofHours(2));
        var atLeastOnce =
                RunRetries.decide(run(1), "PF-CONN-0003", submission("at-least-once", 3, "auto", null), true, now);
        assertThat(atLeastOnce.retry()).isFalse();
        assertThat(atLeastOnce.reason()).contains("delivered at least once");

        assertThat(RunRetries.decide(
                                run(1), "PF-CONN-0003", submission("at-least-once", 3, "checkpoint", null), true, now)
                        .fromCheckpoint())
                .as("asked for: from the checkpoint")
                .isTrue();
        var restart =
                RunRetries.decide(run(1), "PF-CONN-0003", submission("at-least-once", 3, "restart", null), true, now);
        assertThat(restart.retry()).isTrue();
        assertThat(restart.fromCheckpoint()).isFalse();

        var late = RunRetries.decide(
                run(1),
                "PF-CONN-0003",
                submission("exactly-once", 3, "auto", Duration.ofHours(1).toMillis()),
                true,
                now);
        assertThat(late.retry()).isFalse();
        assertThat(late.reason()).contains("env.retry.within");
    }
}
