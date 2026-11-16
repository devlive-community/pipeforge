/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

/**
 * The share of the job's rate limit a reader may use: token buckets for rows and bytes that refill
 * at the quota and hold at most {@link #BURST_SECONDS} of it, so a reader cannot burst. Rows are
 * taken as they are read, and the task waits before its next read until the buckets are no longer
 * in debt. Used on the task thread only.
 */
final class Throttle {

    /** The most the buckets hold, in seconds of the quota. */
    static final double BURST_SECONDS = 0.05;

    private double rowsPerSecond;
    private double bytesPerSecond;
    private double rows;
    private double bytes;
    private long refilled = System.nanoTime();

    /** Sets the quota; 0 for no limit. */
    void quota(double rowsPerSecond, double bytesPerSecond, long now) {
        refill(now);
        this.rowsPerSecond = rowsPerSecond;
        this.bytesPerSecond = bytesPerSecond;
        rows = Math.min(rows, capacity(rowsPerSecond));
        bytes = Math.min(bytes, capacity(bytesPerSecond));
    }

    boolean limited() {
        return rowsPerSecond > 0 || bytesPerSecond > 0;
    }

    double rowsPerSecond() {
        return rowsPerSecond;
    }

    double bytesPerSecond() {
        return bytesPerSecond;
    }

    /** A row of {@code size} bytes was read. */
    void took(long size) {
        if (rowsPerSecond > 0) {
            rows -= 1;
        }
        if (bytesPerSecond > 0) {
            bytes -= size;
        }
    }

    /** How long the task waits before it reads again; 0 when it may read now. */
    long waitNanos(long now) {
        if (!limited()) {
            return 0;
        }
        refill(now);
        double seconds = 0;
        if (rowsPerSecond > 0 && rows < 0) {
            seconds = -rows / rowsPerSecond;
        }
        if (bytesPerSecond > 0 && bytes < 0) {
            seconds = Math.max(seconds, -bytes / bytesPerSecond);
        }
        return (long) Math.ceil(seconds * 1e9);
    }

    private void refill(long now) {
        double seconds = Math.max(0, now - refilled) / 1e9;
        refilled = now;
        if (rowsPerSecond > 0) {
            rows = Math.min(capacity(rowsPerSecond), rows + rowsPerSecond * seconds);
        }
        if (bytesPerSecond > 0) {
            bytes = Math.min(capacity(bytesPerSecond), bytes + bytesPerSecond * seconds);
        }
    }

    private static double capacity(double perSecond) {
        return Math.max(1, perSecond * BURST_SECONDS);
    }
}
