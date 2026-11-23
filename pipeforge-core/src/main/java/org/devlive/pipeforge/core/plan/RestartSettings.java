/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.time.Duration;

/**
 * How a job restarts after a failure that may succeed when retried ({@code env.restart.*}).
 *
 * @param strategy when to restart and how long to wait
 * @param attempts {@code fixed-delay}: the restarts in all
 * @param delay {@code fixed-delay} and {@code failure-rate}: the wait before each restart
 * @param initial {@code exponential-backoff}: the wait before the first restart
 * @param max {@code exponential-backoff}: the longest wait
 * @param multiplier {@code exponential-backoff}: how much longer each wait is than the one before
 * @param resetAfter {@code exponential-backoff}: after running this long without a failure, the
 *     next wait is {@code initial} again
 * @param maxFailures {@code failure-rate}: the failures allowed within {@code interval}
 * @param interval {@code failure-rate}: the window the failures are counted in
 */
public record RestartSettings(
        Strategy strategy,
        int attempts,
        Duration delay,
        Duration initial,
        Duration max,
        double multiplier,
        Duration resetAfter,
        int maxFailures,
        Duration interval) {

    /** Restart strategies. */
    public enum Strategy {
        /** Never restart: the first failure fails the run. */
        NONE,
        /** Restart a fixed number of times, waiting the same time before each. */
        FIXED_DELAY,
        /** Restart without a limit, waiting longer each time up to a maximum. */
        EXPONENTIAL_BACKOFF,
        /** Restart as long as there were few enough failures within a window. */
        FAILURE_RATE;

        /** The name as written in jobs, e.g. {@code fixed-delay}. */
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    public static final int DEFAULT_ATTEMPTS = 3;
    public static final Duration DEFAULT_DELAY = Duration.ofSeconds(10);
    public static final Duration DEFAULT_INITIAL = Duration.ofSeconds(1);
    public static final Duration DEFAULT_MAX = Duration.ofMinutes(5);
    public static final double DEFAULT_MULTIPLIER = 2.0;
    public static final Duration DEFAULT_RESET_AFTER = Duration.ofHours(1);
    public static final int DEFAULT_MAX_FAILURES = 3;
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    /** The settings of a job that sets none: {@code fixed-delay}, three restarts ten seconds apart. */
    public static final RestartSettings DEFAULTS = new RestartSettings(
            Strategy.FIXED_DELAY,
            DEFAULT_ATTEMPTS,
            DEFAULT_DELAY,
            DEFAULT_INITIAL,
            DEFAULT_MAX,
            DEFAULT_MULTIPLIER,
            DEFAULT_RESET_AFTER,
            DEFAULT_MAX_FAILURES,
            DEFAULT_INTERVAL);

    public RestartSettings {
        java.util.Objects.requireNonNull(strategy, "strategy");
        if (attempts < 0 || maxFailures < 0) {
            throw new IllegalArgumentException("Restart attempts and failures cannot be negative");
        }
        if (delay.isNegative() || initial.isNegative() || resetAfter.isNegative()) {
            throw new IllegalArgumentException("Restart waits cannot be negative");
        }
        if (initial.isZero() || max.compareTo(initial) < 0 || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Invalid restart window");
        }
        if (!(multiplier >= 1.0) || Double.isInfinite(multiplier)) {
            throw new IllegalArgumentException("The restart multiplier must be at least 1");
        }
    }

    /** Settings of a strategy with the defaults for everything else. */
    public static RestartSettings of(Strategy strategy) {
        return new RestartSettings(
                strategy,
                DEFAULT_ATTEMPTS,
                DEFAULT_DELAY,
                DEFAULT_INITIAL,
                DEFAULT_MAX,
                DEFAULT_MULTIPLIER,
                DEFAULT_RESET_AFTER,
                DEFAULT_MAX_FAILURES,
                DEFAULT_INTERVAL);
    }
}
