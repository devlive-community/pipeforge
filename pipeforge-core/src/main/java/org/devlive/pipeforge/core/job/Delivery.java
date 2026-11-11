/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

/** The delivery guarantee a job requires of its sinks. */
public enum Delivery {
    AT_LEAST_ONCE,
    EXACTLY_ONCE;

    /** The name as written in jobs, e.g. {@code exactly-once}. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
