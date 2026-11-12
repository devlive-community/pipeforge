/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin.check;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * The documentation data of a plugin: its descriptor, and for every component the capabilities,
 * options and option rules. The documentation site renders the generated sections of plugin pages
 * from this JSON.
 */
public final class PluginDocs {

    /** Version of the JSON layout; the site rejects files it does not understand. */
    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private PluginDocs() {}

    /** Builds the documentation model. */
    public static Map<String, Object> model(PluginDescriptor descriptor, List<PluginInspector.Inspected> components) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("format", FORMAT_VERSION);
        model.put("id", descriptor.id());
        model.put("name", descriptor.name());
        model.put("version", descriptor.version());
        model.put("apiVersion", descriptor.apiVersion());
        model.put("vendor", descriptor.vendor());
        model.put("license", descriptor.license());
        model.put("description", descriptor.description());
        if (descriptor.datasource() != null) {
            model.put(
                    "datasource",
                    Map.of(
                            "type",
                            descriptor.datasource().type(),
                            "versions",
                            descriptor.datasource().versions()));
        }
        model.put("drivers", descriptor.drivers());
        List<Map<String, Object>> list = new ArrayList<>();
        for (PluginInspector.Inspected component : components) {
            list.add(component(component));
        }
        model.put("components", list);
        return model;
    }

    private static Map<String, Object> component(PluginInspector.Inspected inspected) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", inspected.component().type().id());
        map.put("identifier", inspected.component().identifier());
        map.put("factory", inspected.component().className());
        map.put("capabilities", inspected.capabilities());
        OptionRule rule = inspected.rule();
        if (rule == null) {
            return map;
        }
        Set<String> required = keys(rule.required());
        Set<String> oneOf =
                rule.exclusive().stream().flatMap(List::stream).map(Option::key).collect(Collectors.toSet());
        Set<String> conditional = rule.conditions().stream()
                .flatMap(c -> c.required().stream())
                .map(Option::key)
                .collect(Collectors.toSet());
        List<Map<String, Object>> options = new ArrayList<>();
        for (Option<?> option : rule.options()) {
            String requirement = required.contains(option.key())
                    ? "required"
                    : oneOf.contains(option.key())
                            ? "one-of"
                            : conditional.contains(option.key()) ? "conditional" : "optional";
            options.add(option(option, requirement));
        }
        map.put("options", options);
        map.put("exclusive", rule.exclusive().stream().map(PluginDocs::keyList).toList());
        List<Map<String, Object>> conditions = new ArrayList<>();
        for (OptionRule.Condition condition : rule.conditions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("option", condition.trigger().key());
            entry.put("value", text(condition.value()));
            entry.put("required", keyList(condition.required()));
            conditions.add(entry);
        }
        map.put("conditions", conditions);
        map.put("nativeNamespaces", rule.nativeNamespaces());
        return map;
    }

    private static Map<String, Object> option(Option<?> option, String requirement) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", option.key());
        map.put("type", option.type().name().toLowerCase(java.util.Locale.ROOT));
        map.put("requirement", requirement);
        map.put("default", option.isSensitive() ? null : text(option.defaultValue()));
        map.put("description", option.description());
        map.put("group", option.group());
        map.put("min", option.min());
        map.put("max", option.max());
        map.put("values", option.enumValues());
        map.put("sensitive", option.isSensitive());
        map.put("deprecatedKeys", option.deprecatedKeys());
        map.put("since", option.since());
        return map;
    }

    private static Set<String> keys(List<Option<?>> options) {
        return options.stream().map(Option::key).collect(Collectors.toSet());
    }

    private static List<String> keyList(List<Option<?>> options) {
        return options.stream().map(Option::key).toList();
    }

    /** Values as users write them: durations as 30s / 5m, enums by name, collections as JSON. */
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

    /** Writes the model as {@code <dir>/<plugin id>.json} and returns the file. */
    public static Path write(Map<String, Object> model, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = file(model, dir);
        Files.writeString(file, json(model));
        return file;
    }

    /**
     * Compares the model with the file {@link #write} would write; returns what is wrong, or {@code
     * null} when the file is up to date.
     */
    public static @Nullable String stale(Map<String, Object> model, Path dir) throws IOException {
        Path file = file(model, dir);
        if (!Files.isRegularFile(file)) {
            return file + " is missing";
        }
        String expected = json(model);
        String actual = Files.readString(file);
        if (actual.equals(expected)) {
            return null;
        }
        String[] want = expected.split("\n");
        String[] got = actual.split("\n");
        int line = 0;
        while (line < want.length && line < got.length && want[line].equals(got[line])) {
            line++;
        }
        String detail = line < want.length ? "expected '" + want[line].strip() + "'" : "unexpected extra lines";
        return file + " is out of date: line " + (line + 1) + " differs, " + detail;
    }

    private static Path file(Map<String, Object> model, Path dir) {
        return dir.resolve(model.get("id") + ".json");
    }

    private static String json(Map<String, Object> model) throws IOException {
        return MAPPER.writeValueAsString(model) + "\n";
    }
}
