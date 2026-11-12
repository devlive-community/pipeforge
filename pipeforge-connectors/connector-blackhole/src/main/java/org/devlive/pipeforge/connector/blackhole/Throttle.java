/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.blackhole;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Lets one row pass every {@code 1 / rate} seconds on average. A write that comes too early waits
 * for its turn; after a pause the throttle catches up with at most a tenth of a second of rows, so
 * a burst never exceeds the rate for long.
 */
class Throttle {

    /** Lets every row pass at once. */
    static final Throttle NONE = new Throttle(0, () -> 0L, nanos -> {}) {
        @Override
        void acquire() {}
    };

    /** Waits for {@code nanos}; may throw when the waiting thread is interrupted. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final long interval;
    private final long burst;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private long next;

    /**
     * @param rowsPerSecond the rate; above 0
     * @param clock nanosecond time, such as {@link System#nanoTime()}
     */
    Throttle(double rowsPerSecond, LongSupplier clock, Sleeper sleeper) {
        this.interval = rowsPerSecond > 0 ? Math.max(1, Math.round(TimeUnit.SECONDS.toNanos(1) / rowsPerSecond)) : 0;
        this.burst = TimeUnit.MILLISECONDS.toNanos(100);
        this.clock = clock;
        this.sleeper = sleeper;
        this.next = clock.getAsLong();
    }

    static Throttle system(double rowsPerSecond) {
        return new Throttle(rowsPerSecond, System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos));
    }

    /** Waits until the next row may pass. */
    void acquire() throws InterruptedException {
        long now = clock.getAsLong();
        next = Math.max(next, now - burst);
        long wait = next - now;
        if (wait > 0) {
            sleeper.sleep(wait);
        }
        next += interval;
    }
}
