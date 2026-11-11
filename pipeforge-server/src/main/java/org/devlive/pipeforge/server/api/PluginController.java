/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.PluginComponents;
import org.devlive.pipeforge.plugin.InstalledPlugin;
import org.devlive.pipeforge.plugin.PluginManager;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Installed plugins and the options of their components. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "plugins", description = "Installed plugins and their components")
public class PluginController {

    private final PluginManager plugins;

    public PluginController(PluginManager plugins) {
        this.plugins = plugins;
    }

    /**
     * The installed plugins and the plugin packages that could not be used.
     *
     * @param plugins usable plugins, in installation order
     * @param unavailable packages that were rejected, with the reason
     */
    public record PluginList(List<PluginInfo> plugins, List<UnavailablePlugin> unavailable) {}

    /** One installed plugin, as described by its plugin.yaml. */
    public record PluginInfo(
            String id,
            String name,
            String version,
            String apiVersion,
            @Nullable String vendor,
            @Nullable String license,
            @Nullable String description,
            List<ComponentRef> components,
            @Nullable DatasourceInfo datasource,
            List<String> drivers) {}

    /** A component a plugin provides: type (source, transform, sink, ...) and identifier. */
    public record ComponentRef(String type, String identifier) {}

    /** The data source a plugin connects to and the versions verified end to end. */
    public record DatasourceInfo(String type, List<String> versions) {}

    /** A plugin package that cannot be used. */
    public record UnavailablePlugin(String location, String reason) {}

    /**
     * The options of a component, the same model as the generated plugin pages.
     *
     * @param plugin {@code id@version} of the plugin
     * @param exclusive groups of options of which exactly one must be set
     * @param conditions options that become required when another option has a value
     * @param nativeNamespaces prefixes under which native configuration is passed through
     */
    public record ComponentInfo(
            String plugin,
            String type,
            String identifier,
            List<OptionInfo> options,
            List<List<String>> exclusive,
            List<ConditionInfo> conditions,
            List<String> nativeNamespaces) {}

    /**
     * One option. {@code requirement} is required, one-of, conditional or optional; the default of
     * a sensitive option is never shown.
     */
    public record OptionInfo(
            String key,
            String type,
            String requirement,
            @Nullable Object defaultValue,
            String description,
            @Nullable String group,
            @Nullable Double min,
            @Nullable Double max,
            List<String> values,
            boolean sensitive,
            List<String> deprecatedKeys,
            @Nullable String since) {}

    /** When {@code option} has {@code value}, the {@code required} options must be set. */
    public record ConditionInfo(String option, @Nullable Object value, List<String> required) {}

    @GetMapping("/plugins")
    @Operation(
            operationId = "listPlugins",
            summary = "List installed plugins",
            description = "Every usable plugin with its components, and the packages that were rejected.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "列出已安装的插件"),
                                @ExtensionProperty(name = "description", value = "每个可用插件及其组件，以及被拒绝加载的插件包。")
                            }))
    public PluginList plugins() {
        List<PluginInfo> list =
                plugins.plugins().stream().map(PluginController::info).toList();
        List<UnavailablePlugin> unavailable = plugins.unavailable().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new UnavailablePlugin(e.getKey(), e.getValue()))
                .toList();
        return new PluginList(list, unavailable);
    }

    @GetMapping("/components/{type}/{identifier}")
    @Operation(
            operationId = "getComponent",
            summary = "Describe a component",
            description = "The options of a source, transform or sink: types, defaults, requirements and the"
                    + " rules between them.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "查看组件"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "Source、Transform 或 Sink 的选项：类型、默认值、是否必填以及选项之间的规则。")
                            }))
    public ComponentInfo component(
            @Parameter(description = "source, transform or sink") @PathVariable String type,
            @Parameter(description = "The component identifier, e.g. jdbc") @PathVariable String identifier,
            @Parameter(description = "Plugin id or id@version; required when several plugins provide it")
                    @RequestParam(required = false)
                    @Nullable
                    String plugin) {
        NodeKind kind = kind(type);
        try (PluginComponents components = new PluginComponents(plugins, "api")) {
            Component component = components.resolve(kind, identifier, plugin);
            OptionRule rule = component.call(
                    "read the options of " + identifier,
                    () -> component.factory().optionRule());
            return describe(component.plugin(), kind.id(), identifier, rule);
        }
    }

    static NodeKind kind(String type) {
        for (NodeKind kind : NodeKind.values()) {
            if (kind.id().equals(type)) {
                return kind;
            }
        }
        throw PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                .param("reason", "unknown component type '" + type + "'; use source, transform or sink")
                .build();
    }

    static ComponentInfo describe(String plugin, String type, String identifier, OptionRule rule) {
        Set<String> required = keys(rule.required());
        Set<String> oneOf =
                rule.exclusive().stream().flatMap(List::stream).map(Option::key).collect(Collectors.toSet());
        Set<String> conditional = rule.conditions().stream()
                .flatMap(c -> c.required().stream())
                .map(Option::key)
                .collect(Collectors.toSet());
        List<OptionInfo> options = rule.options().stream()
                .map(option -> {
                    String requirement = required.contains(option.key())
                            ? "required"
                            : oneOf.contains(option.key())
                                    ? "one-of"
                                    : conditional.contains(option.key()) ? "conditional" : "optional";
                    return new OptionInfo(
                            option.key(),
                            option.type().name().toLowerCase(Locale.ROOT),
                            requirement,
                            option.isSensitive() ? null : text(option.defaultValue()),
                            option.description(),
                            option.group(),
                            option.min(),
                            option.max(),
                            option.enumValues(),
                            option.isSensitive(),
                            option.deprecatedKeys(),
                            option.since());
                })
                .toList();
        List<List<String>> exclusive =
                rule.exclusive().stream().map(group -> keyList(group)).toList();
        List<ConditionInfo> conditions = rule.conditions().stream()
                .map(c -> new ConditionInfo(c.trigger().key(), text(c.value()), keyList(c.required())))
                .toList();
        return new ComponentInfo(plugin, type, identifier, options, exclusive, conditions, rule.nativeNamespaces());
    }

    private static PluginInfo info(InstalledPlugin plugin) {
        PluginDescriptor d = plugin.descriptor();
        return new PluginInfo(
                d.id(),
                d.name(),
                d.version(),
                d.apiVersion(),
                d.vendor(),
                d.license(),
                d.description(),
                d.provides().stream()
                        .map(c -> new ComponentRef(c.type().id(), c.identifier()))
                        .toList(),
                d.datasource() == null
                        ? null
                        : new DatasourceInfo(
                                d.datasource().type(), d.datasource().versions()),
                d.drivers());
    }

    private static Set<String> keys(List<Option<?>> options) {
        return options.stream().map(Option::key).collect(Collectors.toSet());
    }

    private static List<String> keyList(List<Option<?>> options) {
        return options.stream().map(Option::key).toList();
    }

    /** Values as users write them: durations as 30s / 5m, enums by name. */
    static @Nullable Object text(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Duration duration) {
            return duration(duration);
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof List || value instanceof Map) {
            return value;
        }
        return String.valueOf(value);
    }

    static String duration(Duration duration) {
        long ms = duration.toMillis();
        if (ms % 86_400_000 == 0 && ms > 0) {
            return ms / 86_400_000 + "d";
        }
        if (ms % 3_600_000 == 0 && ms > 0) {
            return ms / 3_600_000 + "h";
        }
        if (ms % 60_000 == 0 && ms > 0) {
            return ms / 60_000 + "m";
        }
        if (ms % 1000 == 0) {
            return ms / 1000 + "s";
        }
        return ms + "ms";
    }
}
