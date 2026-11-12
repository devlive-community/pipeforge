/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.function;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.devlive.pipeforge.api.annotation.Evolving;
import org.devlive.pipeforge.api.factory.JobContext;

/**
 * What a function may depend on besides its arguments. A {@link ScalarFunction} method receives it
 * when its first parameter has this type, and {@link FunctionLifecycle#open} receives it once per
 * parallel instance.
 */
@Evolving
public interface FunctionContext {

    /** The job time zone, for local dates and times and conversions to and from {@code TIMESTAMP_LTZ}. */
    ZoneId timeZone();

    /** The start time of the run; the same for every row, across retries and restores. */
    Instant runStartTime();

    /** The current processing time. */
    Instant now();

    /** The job the function runs in. */
    JobContext job();

    /**
     * Parameters of the function plugin, configured per job; empty for built-in functions and when
     * the job sets none.
     */
    Map<String, String> parameters();
}
