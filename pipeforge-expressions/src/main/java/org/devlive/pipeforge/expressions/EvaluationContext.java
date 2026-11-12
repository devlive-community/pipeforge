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
import java.util.Map;
import java.util.Objects;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.function.FunctionContext;

/**
 * What expressions may depend on besides the row: the job time zone (local dates and times,
 * conversions to and from {@code TIMESTAMP_LTZ}), the start time of the run ({@code
 * RUN_START_TIME()}, constant across retries and restores), the clock for {@code NOW()}, the job and
 * the parameters of function plugins.
 *
 * <p>Functions receive it as their {@link FunctionContext} when their implementation declares one
 * as first parameter, and in {@link org.devlive.pipeforge.api.function.FunctionLifecycle#open}.
 *
 * @param job the job; its time zone is not used, {@code timeZone} is
 * @param parameters parameters of the function plugins, empty when the job sets none
 */
public record EvaluationContext(
        ZoneId timeZone, Instant runStartTime, Clock clock, JobContext job, Map<String, String> parameters)
        implements FunctionContext {

    /** Job name of contexts created without a job. */
    public static final String NO_JOB = "expressions";

    public EvaluationContext {
        Objects.requireNonNull(timeZone, "timeZone");
        Objects.requireNonNull(runStartTime, "runStartTime");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(job, "job");
        parameters = Map.copyOf(parameters);
    }

    /** A context outside a job: a single instance named {@link #NO_JOB}, no function parameters. */
    public EvaluationContext(ZoneId timeZone, Instant runStartTime, Clock clock) {
        this(timeZone, runStartTime, clock, new JobContext(NO_JOB, 1, timeZone), Map.of());
    }

    /** A context in {@code zone} whose run starts now, with the system clock. */
    public static EvaluationContext of(ZoneId zone) {
        Clock clock = Clock.systemUTC();
        return new EvaluationContext(zone, clock.instant(), clock);
    }

    public static EvaluationContext utc() {
        return of(ZoneId.of("UTC"));
    }

    /** This context for a job. */
    public EvaluationContext withJob(JobContext job) {
        return new EvaluationContext(timeZone, runStartTime, clock, job, parameters);
    }

    /** This context with the parameters of the function plugins. */
    public EvaluationContext withParameters(Map<String, String> parameters) {
        return new EvaluationContext(timeZone, runStartTime, clock, job, parameters);
    }

    /** The current processing time. */
    @Override
    public Instant now() {
        return clock.instant();
    }
}
