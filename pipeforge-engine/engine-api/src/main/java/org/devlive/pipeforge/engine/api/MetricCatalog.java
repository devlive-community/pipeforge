/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The catalog of engine metrics in {@code metrics/catalog.yaml}: name, scope, type, unit and a
 * description in every documentation language. The metrics reference of the documentation site is
 * generated from it, and a test checks that every metric the engine reports is in it.
 */
public final class MetricCatalog {

    /** The resource the catalog is read from. */
    public static final String RESOURCE = "metrics/catalog.yaml";

    static final Set<String> SCOPES = Set.of("job", "node");
    static final Set<String> TYPES = Set.of("counter", "gauge", "meter", "timer");
    static final Set<String> UNITS = Set.of("rows", "ms", "rows/s", "ratio", "tasks");
    static final Set<String> LANGUAGES = Set.of("en-US", "zh-CN");
    private static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]*");

    private static final MetricCatalog GLOBAL = load();

    private final List<MetricDefinition> metrics;

    MetricCatalog(List<MetricDefinition> metrics) {
        this.metrics = List.copyOf(metrics);
    }

    /** The catalog of this engine version. */
    public static MetricCatalog global() {
        return GLOBAL;
    }

    public List<MetricDefinition> metrics() {
        return metrics;
    }

    public Optional<MetricDefinition> find(String scope, String name) {
        return metrics.stream()
                .filter(m -> m.scope().equals(scope) && m.name().equals(name))
                .findFirst();
    }

    private static MetricCatalog load() {
        try (InputStream in = MetricCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    /**
     * Parses and checks a catalog.
     *
     * @throws IllegalArgumentException listing every problem: unknown scopes, types or units,
     *     malformed or duplicate names, missing descriptions
     */
    static MetricCatalog parse(String yaml) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(root instanceof List<?> entries)) {
            throw new IllegalArgumentException("The metric catalog must be a list");
        }
        List<String> problems = new ArrayList<>();
        List<MetricDefinition> metrics = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                problems.add("entry is not a mapping: " + entry);
                continue;
            }
            String name = String.valueOf(map.get("name"));
            String scope = String.valueOf(map.get("scope"));
            String type = String.valueOf(map.get("type"));
            String unit = String.valueOf(map.get("unit"));
            String where = scope + "." + name;
            if (!NAME.matcher(name).matches()) {
                problems.add(where + ": the name must be lower camel case");
            }
            if (!SCOPES.contains(scope)) {
                problems.add(where + ": unknown scope '" + scope + "'");
            }
            if (!TYPES.contains(type)) {
                problems.add(where + ": unknown type '" + type + "'");
            }
            if (!UNITS.contains(unit)) {
                problems.add(where + ": unknown unit '" + unit + "'");
            }
            if (seen.put(where, true) != null) {
                problems.add(where + ": listed twice");
            }
            Map<String, String> description = new LinkedHashMap<>();
            if (map.get("description") instanceof Map<?, ?> texts) {
                texts.forEach((k, v) -> description.put(String.valueOf(k), String.valueOf(v)));
            }
            for (String language : LANGUAGES) {
                String text = description.get(language);
                if (text == null || text.isBlank()) {
                    problems.add(where + ": no " + language + " description");
                }
            }
            metrics.add(new MetricDefinition(name, scope, type, unit, description));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid metric catalog:\n  " + String.join("\n  ", problems));
        }
        return new MetricCatalog(metrics);
    }
}
