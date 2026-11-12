/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.core.job.JobDefinition;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.Planner;
import org.devlive.pipeforge.core.plan.PluginComponents;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

/** How a job file is turned into a plan: variables and the planned time. */
public final class JobOptions {

    @Option(names = "--var", paramLabel = "<name=value>", descriptionKey = "option.var")
    Map<String, String> variables = new LinkedHashMap<>();

    @Option(
            names = "--scheduled-time",
            paramLabel = "<time>",
            descriptionKey = "option.scheduled-time",
            converter = InstantConverter.class)
    @Nullable
    Instant scheduledTime;

    /** Parses ISO-8601 instants such as 2026-11-07T00:00:00Z. */
    static final class InstantConverter implements picocli.CommandLine.ITypeConverter<Instant> {
        @Override
        public Instant convert(String value) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e) {
                throw new TypeConversionException(
                        "'" + value + "' is not an ISO-8601 time such as 2026-11-07T00:00:00Z");
            }
        }
    }

    VariableContext variables(Session session) {
        VariableContext.Builder context = VariableContext.builder()
                .environment(session.environment().variables())
                .overrides(variables);
        if (scheduledTime != null) {
            context.scheduledTime(scheduledTime);
        }
        return context.build();
    }

    /**
     * Parses and plans a job with the session's plugins. The components stay leased until {@code
     * components} is closed, so the plan can run.
     */
    JobPlan plan(Session session, String definition, PluginComponents[] components) {
        JobDefinition job = JobParser.parse(definition, variables(session));
        PluginComponents leased = new PluginComponents(session.plugins(), "cli:" + job.name());
        components[0] = leased;
        return new Planner(leased).plan(job);
    }
}
