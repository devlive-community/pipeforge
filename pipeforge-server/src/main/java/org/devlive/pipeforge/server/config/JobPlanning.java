/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.config;

import java.time.Instant;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.JobDefinition;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.Planner;
import org.devlive.pipeforge.core.plan.PluginComponents;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.devlive.pipeforge.plugin.PluginManager;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Plans job definitions with the server's plugins and defaults, for validation, plans and runs. */
@Component
public class JobPlanning {

    private final PluginManager plugins;
    private final ServerConfiguration.JobDefaults defaults;

    public JobPlanning(PluginManager plugins, ServerConfiguration.JobDefaults defaults) {
        this.plugins = plugins;
        this.defaults = defaults;
    }

    /**
     * The plan of a definition.
     *
     * @param variables values for job variables; they win over env.variables
     * @param scheduledTime the planned time of the run, the base of date variables; now when null
     * @throws PipeForgeException {@code PF-CONF-0007} without a definition, else what planning reports
     */
    public JobPlan plan(
            @Nullable String definition, @Nullable Map<String, String> variables, @Nullable Instant scheduledTime) {
        JobDefinition job = parse(definition, variables, scheduledTime);
        try (PluginComponents components = new PluginComponents(plugins, "api:" + job.name())) {
            return plan(job, components);
        }
    }

    /** Plans a definition, e.g. one rewritten for a preview, with the components of {@code components}. */
    public JobPlan plan(JobDefinition job, org.devlive.pipeforge.core.plan.ComponentProvider components) {
        return new Planner(components, defaults.cluster()).plan(job);
    }

    /** The plugins of the server, for components that outlive a plan. */
    public PluginManager plugins() {
        return plugins;
    }

    /**
     * Parses a definition and resolves its variables.
     *
     * @throws PipeForgeException {@code PF-CONF-0007} without a definition, else what parsing reports
     */
    public JobDefinition parse(
            @Nullable String definition, @Nullable Map<String, String> variables, @Nullable Instant scheduledTime) {
        if (definition == null || definition.isBlank()) {
            throw PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                    .param("reason", "definition must hold the job in YAML or JSON")
                    .build();
        }
        VariableContext.Builder context = VariableContext.builder().defaultTimeZone(defaults.timeZone());
        if (variables != null) {
            context.overrides(variables);
        }
        if (scheduledTime != null) {
            context.scheduledTime(scheduledTime);
        }
        return JobParser.parse(definition, context.build());
    }
}
