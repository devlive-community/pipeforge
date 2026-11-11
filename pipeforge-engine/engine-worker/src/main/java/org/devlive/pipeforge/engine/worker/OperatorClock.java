/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Measures the self time of the operators of a chain on the task thread: the time spent in each
 * operator's own code, excluding the operators it calls.
 *
 * <p>Only every {@value #SAMPLE_EVERY}th read is measured, and its times count {@value
 * #SAMPLE_EVERY} times, so that the hot path does not read the clock for every row. During a
 * measured read every switch between operators charges the time since the previous switch to the
 * operator that was running.
 */
final class OperatorClock {

    static final int SAMPLE_EVERY = 16;
    private static final int NOT_SAMPLING = -2;
    private static final int NONE = -1;

    private final AtomicLongArray selfNanos;
    private boolean sampling;
    private int current = NONE;
    private long last;
    private long polls;

    /** @param slots the number of operators, plus slots for time that belongs to no operator */
    OperatorClock(int slots) {
        selfNanos = new AtomicLongArray(slots);
    }

    /** Starts a read by the source in {@code slot}; returns whether it is measured. */
    boolean beginRead(int slot) {
        if (++polls % SAMPLE_EVERY != 0) {
            return false;
        }
        sampling = true;
        current = slot;
        last = System.nanoTime();
        return true;
    }

    void endRead() {
        if (sampling) {
            charge();
            sampling = false;
            current = NONE;
        }
    }

    /** Control enters the operator in {@code slot}; returns the token to pass to {@link #exit}. */
    int enter(int slot) {
        if (!sampling) {
            return NOT_SAMPLING;
        }
        charge();
        int previous = current;
        current = slot;
        return previous;
    }

    /** Control returns to the operator that called {@link #enter}. */
    void exit(int token) {
        if (token == NOT_SAMPLING || !sampling) {
            return;
        }
        charge();
        current = token;
    }

    long selfNanos(int slot) {
        return selfNanos.get(slot);
    }

    private void charge() {
        long now = System.nanoTime();
        if (current >= 0) {
            selfNanos.addAndGet(current, (now - last) * SAMPLE_EVERY);
        }
        last = now;
    }
}
