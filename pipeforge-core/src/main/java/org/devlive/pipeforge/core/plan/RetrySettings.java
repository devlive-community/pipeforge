/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How a failed run starts a new attempt ({@code env.retry}, design 34 section 3), once the
 * restarts of its tasks are used up or its failure was not one of tasks.
 *
 * @param attempts the retries in all; 0 for none
 * @param initial the wait before the first retry
 * @param max the longest wait
 * @param multiplier how much longer each wait is than the one before
 * @param on the error categories ({@code CONN}), error codes ({@code PF-CONN-0002}) or the kind
 *     {@code TRANSIENT} whose failures are retried
 * @param resume where a new attempt starts
 * @param within how long after the run was submitted it may still be retried; {@code null} for no
 *     limit
 */
public record RetrySettings(
        int attempts,
        Duration initial,
        Duration max,
        double multiplier,
        List<String> on,
        Resume resume,
        @Nullable Duration within) {

    /** Where a new attempt starts. */
    public enum Resume {
        /**
         * From the last completed checkpoint when every sink commits in two phases or writes
         * idempotently, else from the start under the same condition; a job delivered at least once
         * is not retried, as either would write rows again.
         */
        AUTO,
        /** From the last completed checkpoint, or from the start without one. */
        CHECKPOINT,
        /** From the start. */
        RESTART;

        /** The name as written in jobs, e.g. {@code checkpoint}. */
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final Duration DEFAULT_INITIAL = Duration.ofMinutes(1);
    public static final Duration DEFAULT_MAX = Duration.ofMinutes(30);
    public static final double DEFAULT_MULTIPLIER = 2.0;
    public static final List<String> DEFAULT_ON = List.of("CONN", "RES", "CLUS", "TRANSIENT");

    /** No retries. */
    public static final RetrySettings NONE =
            new RetrySettings(0, DEFAULT_INITIAL, DEFAULT_MAX, DEFAULT_MULTIPLIER, DEFAULT_ON, Resume.AUTO, null);

    public RetrySettings {
        on = List.copyOf(on);
        if (attempts < 0 || initial.isNegative() || max.compareTo(initial) < 0 || !(multiplier >= 1.0)) {
            throw new IllegalArgumentException("Invalid retry settings");
        }
        java.util.Objects.requireNonNull(resume, "resume");
    }

    /** The wait before retry {@code retry}, counted from 1. */
    public Duration backoff(int retry) {
        double millis = initial.toMillis() * Math.pow(multiplier, Math.max(0, retry - 1));
        return millis >= max.toMillis() ? max : Duration.ofMillis((long) millis);
    }
}
