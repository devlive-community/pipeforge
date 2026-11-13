/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.JobDefinition;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.ComponentProvider;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.Planner;
import org.devlive.pipeforge.core.plan.PluginComponents;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.devlive.pipeforge.plugin.PluginManager;
import org.jspecify.annotations.Nullable;

/**
 * Plans a submitted definition with the plugins of the master and the plugin versions the server
 * chose: a component of a pinned plugin is taken from exactly that version, and a version the
 * master does not have fails the run with {@link ErrorCode#PLUGIN_VERSION_MISMATCH}.
 */
public final class PluginPlanning implements Dispatcher.Planning {

    private final PluginManager plugins;

    public PluginPlanning(PluginManager plugins) {
        this.plugins = plugins;
    }

    @Override
    public Dispatcher.Planned plan(Dispatcher.Submission submission) {
        VariableContext.Builder variables = VariableContext.builder().overrides(submission.variables());
        if (submission.scheduledTime() != null) {
            variables.scheduledTime(submission.scheduledTime());
        }
        JobDefinition job = JobParser.parse(submission.definition(), variables.build());
        Pinned components =
                new Pinned(new PluginComponents(plugins, "run:" + submission.runId()), submission.plugins());
        try {
            JobPlan plan = new Planner(components).plan(job);
            return new Dispatcher.Planned(plan, components);
        } catch (RuntimeException e) {
            components.close();
            throw e;
        }
    }

    /** Resolves components from the pinned version of their plugin. */
    static final class Pinned implements ComponentProvider {
        private final ComponentProvider delegate;
        private final Map<String, String> pins;

        Pinned(ComponentProvider delegate, Map<String, String> pins) {
            this.delegate = delegate;
            this.pins = Map.copyOf(pins);
        }

        @Override
        public Component resolve(NodeKind kind, String type, @Nullable String plugin) {
            Component component = delegate.resolve(kind, type, plugin);
            String coordinate = component.plugin();
            int at = coordinate.lastIndexOf('@');
            if (at < 0) {
                return component;
            }
            String id = coordinate.substring(0, at);
            String version = coordinate.substring(at + 1);
            String wanted = pins.get(id);
            if (wanted == null || wanted.equals(version)) {
                return component;
            }
            try {
                return delegate.resolve(kind, type, id + "@" + wanted);
            } catch (PipeForgeException e) {
                throw PipeForgeException.of(ErrorCode.PLUGIN_VERSION_MISMATCH)
                        .param("plugin", id)
                        .param("wanted", wanted)
                        .param("installed", version)
                        .cause(e)
                        .build();
            }
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
