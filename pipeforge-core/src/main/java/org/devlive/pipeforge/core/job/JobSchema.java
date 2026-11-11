/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.OptionType;
import org.jspecify.annotations.Nullable;

/**
 * The JSON Schema (draft 2020-12) of the job DSL, for editors: field names, types, patterns and
 * descriptions of the job, of env and of the node fields, plus the options of every component
 * passed in. It describes what {@link JobParser} accepts; the parser and the planner remain the
 * authority. Variables are allowed wherever the parser allows them: every constrained field also
 * accepts a text containing {@code ${...}}. Choices of enumerated fields are repeated in {@code
 * examples} for completion. Descriptions are in English, with a Chinese one in {@code x-zh-CN} for
 * the fields of the DSL itself.
 */
public final class JobSchema {

    /** The JSON Schema dialect. */
    public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /** A text with a variable, accepted wherever the parser resolves variables. */
    private static final Map<String, Object> VARIABLE = Map.of("type", "string", "pattern", "\\$\\{");

    private static final Map<NodeKind, String> KIND_ZH =
            Map.of(NodeKind.SOURCE, "Source 节点", NodeKind.TRANSFORM, "Transform 节点", NodeKind.SINK, "Sink 节点");

    private JobSchema() {}

    /**
     * Builds the schema.
     *
     * @param components for each node kind, component identifier to its option rule
     */
    public static Map<String, Object> build(Map<NodeKind, Map<String, OptionRule>> components) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "version", described(Map.of("const", JobParser.VERSION), "The DSL version; always 1", "DSL 版本，固定为 1"));
        properties.put(
                "name",
                described(
                        pattern(JobParser.NAME.pattern()),
                        "The job name: letters, digits, '_', '.' and '-'",
                        "作业名称：字母、数字、'_'、'.' 和 '-'"));
        properties.put("description", described(Map.of("type", "string"), "Free text", "说明文字"));
        properties.put("env", described(ref("env"), "Job settings and defaults for nodes", "作业设置和节点默认值"));
        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("env", env(components));
        for (NodeKind kind : NodeKind.values()) {
            String node = kind.id() + "Node";
            defs.put(node, node(kind, components.getOrDefault(kind, Map.of())));
            Map<String, Object> list = new LinkedHashMap<>();
            list.put("oneOf", List.of(Map.of("type", "array", "items", ref(node)), ref(node)));
            properties.put(kind.id(), described(list, "The " + kind.id() + " nodes", KIND_ZH.get(kind)));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", DIALECT);
        schema.put("title", "PipeForge job");
        schema.put("type", "object");
        schema.put("required", List.of("version", "name", "source", "sink"));
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("$defs", defs);
        return schema;
    }

    private static Map<String, Object> env(Map<NodeKind, Map<String, OptionRule>> components) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Option<?> option : EnvOptions.RULE.options()) {
            Map<String, Object> property;
            if (option.equals(EnvOptions.VARIABLES)) {
                property = new LinkedHashMap<>();
                property.put("type", "object");
                property.put("additionalProperties", Map.of("type", List.of("string", "number", "boolean")));
                property.put("description", option.description());
            } else {
                property = option(option);
            }
            properties.put(option.key(), property);
        }
        for (NodeKind kind : NodeKind.values()) {
            Map<String, Object> defaults = new TreeMap<>();
            components.getOrDefault(kind, Map.of()).values().forEach(rule -> rule.options()
                    .forEach(o -> defaults.putIfAbsent(o.key(), option(o))));
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", "object");
            property.put("description", "Defaults for the options of every " + kind.id() + " that declares them");
            property.put("properties", defaults);
            properties.put(kind.id(), property);
        }
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("type", "object");
        env.put("properties", properties);
        env.put("patternProperties", Map.of("^(source|transform|sink)\\.", Map.of()));
        env.put("additionalProperties", false);
        return env;
    }

    private static Map<String, Object> node(NodeKind kind, Map<String, OptionRule> components) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "id",
                described(
                        pattern(JobParser.ID.pattern()),
                        "The stable id of the node; checkpoint state is matched by it",
                        "节点的稳定 id，checkpoint 状态按它匹配"));
        Map<String, Object> type = pattern(JobParser.TYPE.pattern());
        if (!components.isEmpty()) {
            type.put("examples", new ArrayList<>(new TreeMap<>(components).keySet()));
        }
        properties.put("type", described(type, "The component, e.g. jdbc", "组件，例如 jdbc"));
        properties.put(
                "plugin",
                described(
                        pattern(JobParser.PLUGIN.pattern()),
                        "Pins the plugin: id or id@version",
                        "指定插件：id 或 id@version"));
        if (kind != NodeKind.SOURCE) {
            properties.put(
                    "inputs",
                    described(
                            Map.of(
                                    "oneOf",
                                    List.of(
                                            Map.of("type", "string"),
                                            Map.of("type", "array", "items", Map.of("type", "string")))),
                            "Ids of the upstream nodes; the preceding source or transform when omitted",
                            "上游节点的 id；省略时为前面最近的 Source 或 Transform"));
        }
        properties.put(
                "parallelism",
                described(
                        orVariable(Map.of("type", "integer", "minimum", 1, "maximum", EnvOptions.MAX_PARALLELISM)),
                        "Overrides env.parallelism for this node",
                        "覆盖该节点的 env.parallelism"));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "object");
        node.put("required", List.of("id", "type"));
        node.put("properties", properties);
        List<Object> perType = new ArrayList<>();
        new TreeMap<>(components).forEach((identifier, rule) -> {
            Map<String, Object> options = new LinkedHashMap<>();
            for (Option<?> option : rule.options()) {
                options.put(option.key(), option(option));
                for (String deprecated : option.deprecatedKeys()) {
                    Map<String, Object> old = option(option);
                    old.put("deprecated", true);
                    options.put(deprecated, old);
                }
            }
            perType.add(Map.of(
                    "if",
                    Map.of("properties", Map.of("type", Map.of("const", identifier)), "required", List.of("type")),
                    "then",
                    Map.of("properties", options)));
        });
        if (!perType.isEmpty()) {
            node.put("allOf", perType);
        }
        return node;
    }

    /**
     * The schema of one option: the JSON type with range and choices (or a text with a variable),
     * the choices again as examples, the default (never for secrets) and the description.
     */
    static Map<String, Object> option(Option<?> option) {
        Map<String, Object> base = new LinkedHashMap<>();
        List<String> choices = List.of();
        switch (option.type()) {
            case STRING -> base.put("type", "string");
            case INT, LONG -> base.put("type", "integer");
            case DOUBLE -> base.put("type", "number");
            case BOOLEAN -> base.put("type", "boolean");
            case DURATION -> {
                base.put("type", "string");
                base.put("pattern", "^\\d+\\s*(ms|s|m|h|d)$");
            }
            case ENUM -> {
                choices = option.enumValues().stream().map(JobSchema::enumText).toList();
                base.put("type", "string");
                base.put("enum", choices);
            }
            case LIST -> base.put("type", List.of("array", "string"));
            case MAP -> base.put("type", "object");
            case OBJECT_LIST -> {
                base.put("type", "array");
                base.put(
                        "items",
                        Map.of(
                                "type",
                                "object",
                                "additionalProperties",
                                Map.of("type", List.of("string", "number", "boolean", "null"))));
            }
        }
        if (option.min() != null) {
            base.put("minimum", number(option.min()));
        }
        if (option.max() != null) {
            base.put("maximum", number(option.max()));
        }
        Map<String, Object> schema = option.type() == OptionType.STRING ? base : orVariable(base);
        if (!choices.isEmpty()) {
            schema.put("examples", choices);
        }
        Object defaultValue = option.isSensitive() ? null : defaultText(option.defaultValue());
        if (defaultValue != null) {
            schema.put("default", defaultValue);
        }
        if (option.isSensitive()) {
            schema.put("writeOnly", true);
        }
        if (!option.description().isEmpty()) {
            schema.put("description", option.description());
        }
        return schema;
    }

    /** Enum constants as written in jobs: AT_LEAST_ONCE is at-least-once. */
    static String enumText(String constant) {
        return constant.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static @Nullable Object defaultText(@Nullable Object value) {
        if (value instanceof Duration duration) {
            return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + "s" : duration.toMillis() + "ms";
        }
        if (value instanceof Enum<?> e) {
            return enumText(e.name());
        }
        return value instanceof Map<?, ?> map && map.isEmpty() ? null : value;
    }

    /** Whole numbers are written without a fraction: 10, not 10.0. */
    private static Number number(Double value) {
        if (value == Math.rint(value) && Math.abs(value) < Long.MAX_VALUE) {
            return value.longValue();
        }
        return value;
    }

    /** A text matching {@code regex} as a whole, or a text with a variable. */
    private static Map<String, Object> pattern(String regex) {
        return orVariable(Map.of("type", "string", "pattern", "^" + regex + "$"));
    }

    /** {@code base}, or a text with a variable. */
    private static Map<String, Object> orVariable(Map<String, Object> base) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("anyOf", List.of(base, VARIABLE));
        return schema;
    }

    private static Map<String, Object> ref(String definition) {
        return new LinkedHashMap<>(Map.of("$ref", "#/$defs/" + definition));
    }

    private static Map<String, Object> described(Map<String, Object> schema, String english, String chinese) {
        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("description", english);
        result.put("x-zh-CN", Map.of("description", chinese));
        return result;
    }
}
