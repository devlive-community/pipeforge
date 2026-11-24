/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * How long a run may wait and run ({@code env.timeout}, design 10 section 1.3).
 *
 * @param run how long the run may be running, without the time it waited for slots or was paused,
 *     counted across its attempts; {@code null} for no limit
 * @param queue how long it may wait for slots; {@code null} for the cluster's {@code
 *     scheduler.slot-request-timeout}
 * @param action what happens when the run time is up
 */
public record TimeoutSettings(@Nullable Duration run, @Nullable Duration queue, Action action) {

    /** What happens to a run whose time is up. */
    public enum Action {
        /** Cancelled; it ends {@code TIMEOUT}. */
        CANCEL,
        /**
         * Stopped with a savepoint, then it ends {@code TIMEOUT} and may go on from the savepoint;
         * cancelled when the savepoint fails.
         */
        SAVEPOINT;

        /** The name as written in jobs, e.g. {@code savepoint}. */
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** No limits. */
    public static final TimeoutSettings NONE = new TimeoutSettings(null, null, Action.CANCEL);

    public TimeoutSettings {
        java.util.Objects.requireNonNull(action, "action");
        if ((run != null && !run.isPositive()) || (queue != null && !queue.isPositive())) {
            throw new IllegalArgumentException("Timeouts must be longer than zero");
        }
    }
}
