/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

/**
 * The rate limit of a job ({@code env.limit}): how fast all its sources together may read.
 *
 * @param rowsPerSecond rows per second; 0 for no limit
 * @param bytesPerSecond bytes per second, as rows take them in memory; 0 for no limit
 */
public record RateLimit(double rowsPerSecond, double bytesPerSecond) {

    /** No limit. */
    public static final RateLimit NONE = new RateLimit(0, 0);

    public RateLimit {
        if (rowsPerSecond < 0 || bytesPerSecond < 0 || Double.isNaN(rowsPerSecond) || Double.isNaN(bytesPerSecond)) {
            throw new IllegalArgumentException("a rate limit must be 0 or more");
        }
    }

    public boolean limited() {
        return rowsPerSecond > 0 || bytesPerSecond > 0;
    }
}
