/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.devlive.pipeforge.core.job.JobSchema;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.PluginComponents;
import org.devlive.pipeforge.plugin.InstalledPlugin;
import org.devlive.pipeforge.plugin.PluginManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The JSON Schema of the job DSL with the options of the installed components, for editors. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "jobs", description = "Job validation and plans")
public class JobSchemaController {

    private static final Logger LOG = LoggerFactory.getLogger(JobSchemaController.class);

    private final PluginManager plugins;
    private @Nullable Map<String, Object> schema;

    public JobSchemaController(PluginManager plugins) {
        this.plugins = plugins;
    }

    @GetMapping("/jobs/schema")
    @Operation(
            operationId = "getJobSchema",
            summary = "Get the job schema",
            description = "The JSON Schema (draft 2020-12) of job definitions, with the options of every installed"
                    + " source, transform and sink, for completion in editors. Validation stays with validateJob.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "获取作业 Schema"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "作业定义的 JSON Schema（draft 2020-12），包含每个已安装的 Source、Transform 和 Sink 的选项，"
                                                + "供编辑器补全使用。校验仍以 validateJob 为准。")
                            }))
    public synchronized Map<String, Object> schema() {
        if (schema == null) {
            schema = JobSchema.build(components());
        }
        return schema;
    }

    /** The option rules of the installed components; plugins are loaded once, when first asked. */
    private Map<NodeKind, Map<String, OptionRule>> components() {
        Map<NodeKind, Map<String, OptionRule>> rules = new EnumMap<>(NodeKind.class);
        try (PluginComponents components = new PluginComponents(plugins, "api:schema")) {
            for (InstalledPlugin plugin : plugins.plugins()) {
                for (PluginDescriptor.Component provided : plugin.descriptor().provides()) {
                    NodeKind kind = kind(provided);
                    if (kind == null) {
                        continue;
                    }
                    try {
                        Component component = components.resolve(kind, provided.identifier(), plugin.coordinate());
                        OptionRule rule = component.call(
                                "read the options of " + provided.identifier(),
                                () -> component.factory().optionRule());
                        rules.computeIfAbsent(kind, k -> new LinkedHashMap<>())
                                .putIfAbsent(provided.identifier(), rule);
                    } catch (PipeForgeException e) {
                        LOG.warn(
                                "Leaving {} of {} out of the job schema: {}",
                                provided.identifier(),
                                plugin.coordinate(),
                                e.getMessage());
                    }
                }
            }
        }
        return rules;
    }

    private static @Nullable NodeKind kind(PluginDescriptor.Component component) {
        for (NodeKind kind : NodeKind.values()) {
            if (kind.pluginType() == component.type()) {
                return kind;
            }
        }
        return null;
    }
}
