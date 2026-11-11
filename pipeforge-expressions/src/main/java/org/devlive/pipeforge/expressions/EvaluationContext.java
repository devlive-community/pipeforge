/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * What expressions may depend on besides the row: the job time zone (local dates and times,
 * conversions to and from {@code TIMESTAMP_LTZ}), the start time of the run ({@code
 * RUN_START_TIME()}, constant across retries and restores) and the clock for {@code NOW()}.
 *
 * <p>Functions receive the context when their implementation declares it as first parameter.
 */
public record EvaluationContext(ZoneId timeZone, Instant runStartTime, Clock clock) {

    public EvaluationContext {
        Objects.requireNonNull(timeZone, "timeZone");
        Objects.requireNonNull(runStartTime, "runStartTime");
        Objects.requireNonNull(clock, "clock");
    }

    /** A context in {@code zone} whose run starts now, with the system clock. */
    public static EvaluationContext of(ZoneId zone) {
        Clock clock = Clock.systemUTC();
        return new EvaluationContext(zone, clock.instant(), clock);
    }

    public static EvaluationContext utc() {
        return of(ZoneId.of("UTC"));
    }

    /** The current processing time. */
    public Instant now() {
        return clock.instant();
    }
}
