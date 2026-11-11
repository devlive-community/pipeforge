/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.factory;

import java.time.ZoneId;
import org.devlive.pipeforge.api.annotation.Stable;

/**
 * Job-level information available to every plugin.
 *
 * @param jobName the name of the job
 * @param parallelism the default parallelism of the job
 * @param timeZone the job time zone, used for local date and time values
 */
@Stable
public record JobContext(String jobName, int parallelism, ZoneId timeZone) {

    /** A context for tests and local runs: parallelism 1 in UTC. */
    public static JobContext local(String jobName) {
        return new JobContext(jobName, 1, ZoneId.of("UTC"));
    }
}
