/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.variable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Everything variables are resolved from, apart from the job itself.
 *
 * @param overrides values for ${var:name} given when the job is run; they win over env.variables
 * @param scheduledTime the planned time of the run, the base of ${date:...}: the trigger time of a
 *     scheduled run, or the day being backfilled, not the moment the run actually starts
 * @param environment the environment variables seen by ${env:NAME}
 * @param run the run seen by ${run:...}
 * @param defaultTimeZone the time zone of jobs that do not set env.timezone
 */
public record VariableContext(
        Map<String, String> overrides,
        Instant scheduledTime,
        Map<String, String> environment,
        RunInfo run,
        ZoneId defaultTimeZone) {

    public VariableContext {
        overrides = Map.copyOf(overrides);
        Objects.requireNonNull(scheduledTime, "scheduledTime");
        environment = Map.copyOf(environment);
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(defaultTimeZone, "defaultTimeZone");
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder of {@link VariableContext}; defaults to now, the process environment and UTC. */
    public static final class Builder {
        private final Map<String, String> overrides = new LinkedHashMap<>();
        private Clock clock = Clock.systemUTC();
        private @Nullable Instant scheduledTime;
        private Map<String, String> environment = System.getenv();
        private @Nullable RunInfo run;
        private ZoneId defaultTimeZone = ZoneId.of("UTC");

        private Builder() {}

        public Builder override(String name, String value) {
            overrides.put(name, value);
            return this;
        }

        public Builder overrides(Map<String, String> values) {
            overrides.putAll(values);
            return this;
        }

        public Builder clock(Clock value) {
            clock = value;
            return this;
        }

        public Builder scheduledTime(Instant value) {
            scheduledTime = value;
            return this;
        }

        public Builder environment(Map<String, String> value) {
            environment = value;
            return this;
        }

        public Builder run(RunInfo value) {
            run = value;
            return this;
        }

        public Builder defaultTimeZone(ZoneId value) {
            defaultTimeZone = value;
            return this;
        }

        public VariableContext build() {
            Instant now = clock.instant();
            Instant scheduled = scheduledTime == null ? now : scheduledTime;
            return new VariableContext(
                    overrides, scheduled, environment, run == null ? RunInfo.preview(now) : run, defaultTimeZone);
        }
    }
}
