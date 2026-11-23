/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.failure;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import org.devlive.pipeforge.core.plan.RestartSettings;

/**
 * Decides whether a run restarts after a failure that may succeed when retried, and how long it
 * waits first ({@code env.restart}, design 04 section 10.4). One instance serves one run; it is
 * not thread safe and is used on the job master thread.
 */
public interface RestartStrategy {

    /**
     * Records a failure at {@code nowMs}.
     *
     * @return the wait before the restart, or empty when the strategy allows no more restarts
     */
    Optional<Duration> onFailure(long nowMs);

    /** The strategy of {@code settings}. */
    static RestartStrategy of(RestartSettings settings) {
        return switch (settings.strategy()) {
            case NONE -> now -> Optional.empty();
            case FIXED_DELAY -> new FixedDelay(settings.attempts(), settings.delay());
            case EXPONENTIAL_BACKOFF -> new ExponentialBackoff(
                    settings.initial(), settings.max(), settings.multiplier(), settings.resetAfter());
            case FAILURE_RATE -> new FailureRate(settings.maxFailures(), settings.interval(), settings.delay());
        };
    }

    /** {@code attempts} restarts in all, each after {@code delay}. */
    final class FixedDelay implements RestartStrategy {
        private final int attempts;
        private final Duration delay;
        private int restarts;

        FixedDelay(int attempts, Duration delay) {
            this.attempts = attempts;
            this.delay = delay;
        }

        @Override
        public Optional<Duration> onFailure(long nowMs) {
            if (restarts >= attempts) {
                return Optional.empty();
            }
            restarts++;
            return Optional.of(delay);
        }
    }

    /**
     * Restarts without a limit: the first wait is {@code initial}, each later one {@code
     * multiplier} times the one before up to {@code max}; a failure more than {@code resetAfter}
     * after the one before starts again at {@code initial}.
     */
    final class ExponentialBackoff implements RestartStrategy {
        private final Duration initial;
        private final Duration max;
        private final double multiplier;
        private final Duration resetAfter;
        private Duration next;
        private long lastFailureMs = Long.MIN_VALUE;

        ExponentialBackoff(Duration initial, Duration max, double multiplier, Duration resetAfter) {
            this.initial = initial;
            this.max = max;
            this.multiplier = multiplier;
            this.resetAfter = resetAfter;
            this.next = initial;
        }

        @Override
        public Optional<Duration> onFailure(long nowMs) {
            if (lastFailureMs != Long.MIN_VALUE && nowMs - lastFailureMs > resetAfter.toMillis()) {
                next = initial;
            }
            lastFailureMs = nowMs;
            Duration wait = next;
            double longer = wait.toMillis() * multiplier;
            next = longer >= max.toMillis() ? max : Duration.ofMillis((long) longer);
            return Optional.of(wait);
        }
    }

    /**
     * Restarts after {@code delay} as long as no more than {@code maxFailures} failures happened
     * within the last {@code interval}, this one included.
     */
    final class FailureRate implements RestartStrategy {
        private final int maxFailures;
        private final long intervalMs;
        private final Duration delay;
        private final ArrayDeque<Long> failures = new ArrayDeque<>();

        FailureRate(int maxFailures, Duration interval, Duration delay) {
            this.maxFailures = maxFailures;
            this.intervalMs = interval.toMillis();
            this.delay = delay;
        }

        @Override
        public Optional<Duration> onFailure(long nowMs) {
            failures.addLast(nowMs);
            while (!failures.isEmpty() && nowMs - failures.peekFirst() >= intervalMs) {
                failures.removeFirst();
            }
            return failures.size() > maxFailures ? Optional.empty() : Optional.of(delay);
        }
    }
}
