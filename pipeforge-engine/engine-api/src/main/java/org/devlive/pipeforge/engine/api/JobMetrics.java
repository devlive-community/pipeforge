/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

/**
 * Job-level metrics. This is the skeleton the native engine fills; the counters, rates and
 * bottleneck indicators of the metrics catalog are added here as the engine collects them.
 *
 * @param elapsedMs time since the job was submitted, until it ended
 */
public record JobMetrics(long elapsedMs) {

    public static final JobMetrics EMPTY = new JobMetrics(0);
}
