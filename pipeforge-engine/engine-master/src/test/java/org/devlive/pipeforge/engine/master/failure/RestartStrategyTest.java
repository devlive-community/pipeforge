/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.devlive.pipeforge.core.plan.RestartSettings;
import org.devlive.pipeforge.core.plan.RestartSettings.Strategy;
import org.junit.jupiter.api.Test;

class RestartStrategyTest {

    private static RestartSettings settings(
            Strategy strategy,
            int attempts,
            Duration delay,
            Duration initial,
            Duration max,
            double multiplier,
            Duration resetAfter,
            int maxFailures,
            Duration interval) {
        return new RestartSettings(
                strategy, attempts, delay, initial, max, multiplier, resetAfter, maxFailures, interval);
    }

    @Test
    void noneNeverRestarts() {
        RestartStrategy strategy = RestartStrategy.of(RestartSettings.of(Strategy.NONE));
        assertThat(strategy.onFailure(0)).isEmpty();
        assertThat(strategy.onFailure(1_000_000)).isEmpty();
    }

    @Test
    void fixedDelayRestartsTheGivenNumberOfTimes() {
        RestartStrategy strategy = RestartStrategy.of(RestartSettings.DEFAULTS);
        for (int i = 0; i < 3; i++) {
            assertThat(strategy.onFailure(i * 60_000L)).contains(Duration.ofSeconds(10));
        }
        assertThat(strategy.onFailure(10 * 60_000L)).as("the fourth failure").isEmpty();
        assertThat(strategy.onFailure(Long.MAX_VALUE / 2))
                .as("attempts never come back")
                .isEmpty();

        RestartStrategy never = RestartStrategy.of(settings(
                Strategy.FIXED_DELAY,
                0,
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                2,
                Duration.ofHours(1),
                3,
                Duration.ofMinutes(5)));
        assertThat(never.onFailure(0)).isEmpty();
    }

    @Test
    void exponentialBackoffGrowsUpToTheMaximum() {
        RestartStrategy strategy = RestartStrategy.of(settings(
                Strategy.EXPONENTIAL_BACKOFF,
                3,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                2,
                Duration.ofHours(1),
                3,
                Duration.ofMinutes(5)));
        long now = 0;
        for (long expected : new long[] {10, 20, 40, 60, 60, 60}) {
            now += 1_000;
            assertThat(strategy.onFailure(now)).contains(Duration.ofSeconds(expected));
        }
    }

    @Test
    void exponentialBackoffStartsOverAfterAStableRun() {
        RestartStrategy strategy = RestartStrategy.of(settings(
                Strategy.EXPONENTIAL_BACKOFF,
                3,
                Duration.ofSeconds(10),
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                3,
                Duration.ofMinutes(10),
                3,
                Duration.ofMinutes(5)));
        assertThat(strategy.onFailure(0)).contains(Duration.ofSeconds(1));
        assertThat(strategy.onFailure(60_000)).contains(Duration.ofSeconds(3));
        assertThat(strategy.onFailure(120_000)).contains(Duration.ofSeconds(9));
        long tenMinutes = Duration.ofMinutes(10).toMillis();
        assertThat(strategy.onFailure(120_000 + tenMinutes))
                .as("exactly reset-after later is not yet stable")
                .contains(Duration.ofSeconds(27));
        assertThat(strategy.onFailure(120_000 + 2 * tenMinutes + 1))
                .as("more than reset-after later")
                .contains(Duration.ofSeconds(1));
        assertThat(strategy.onFailure(120_000 + 2 * tenMinutes + 2)).contains(Duration.ofSeconds(3));
    }

    @Test
    void exponentialBackoffWithFractionalMultipliers() {
        RestartStrategy strategy = RestartStrategy.of(settings(
                Strategy.EXPONENTIAL_BACKOFF,
                3,
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                1.5,
                Duration.ofHours(1),
                3,
                Duration.ofMinutes(5)));
        assertThat(strategy.onFailure(1)).contains(Duration.ofSeconds(1));
        assertThat(strategy.onFailure(2)).contains(Duration.ofMillis(1_500));
        assertThat(strategy.onFailure(3)).contains(Duration.ofSeconds(2));
    }

    @Test
    void failureRateCountsTheFailuresWithinTheWindow() {
        Duration delay = Duration.ofSeconds(5);
        RestartStrategy strategy = RestartStrategy.of(settings(
                Strategy.FAILURE_RATE,
                3,
                delay,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                2,
                Duration.ofHours(1),
                2,
                Duration.ofMinutes(1)));
        assertThat(strategy.onFailure(0)).contains(delay);
        assertThat(strategy.onFailure(30_000)).contains(delay);
        assertThat(strategy.onFailure(59_999)).as("three within a minute").isEmpty();

        RestartStrategy spread = RestartStrategy.of(settings(
                Strategy.FAILURE_RATE,
                3,
                delay,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                2,
                Duration.ofHours(1),
                2,
                Duration.ofMinutes(1)));
        for (long at = 0; at < 10 * 60_000L; at += 30_000) {
            assertThat(spread.onFailure(at)).as("two a minute at %d", at).isEqualTo(Optional.of(delay));
        }
        assertThat(spread.onFailure(10 * 60_000L - 1))
                .as("a third within the last minute")
                .isEmpty();
    }

    @Test
    void failureRateWithNoFailuresAllowed() {
        RestartStrategy strategy = RestartStrategy.of(settings(
                Strategy.FAILURE_RATE,
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                2,
                Duration.ofHours(1),
                0,
                Duration.ofMinutes(1)));
        assertThat(strategy.onFailure(0)).isEmpty();
    }
}
