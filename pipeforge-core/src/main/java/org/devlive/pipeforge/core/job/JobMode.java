/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

/** How a job runs: to the end of bounded input, or continuously. */
public enum JobMode {
    BATCH,
    STREAMING;

    /** The name as written in jobs, e.g. {@code batch}. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
