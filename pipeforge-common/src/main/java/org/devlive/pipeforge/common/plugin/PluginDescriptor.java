/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * The {@code plugin.yaml} of a plugin package: identity, the API major version it was built for,
 * and the components it provides.
 *
 * <pre>
 * id: fake
 * name: Fake
 * version: 0.1.0
 * api-version: "0.x"
 * provides:
 *   - { type: source, identifier: fake, class: org.example.FakeSourceFactory }
 * </pre>
 *
 * @param id plugin id, lower case words joined by hyphens
 * @param name display name
 * @param version plugin version (semantic version)
 * @param apiVersion the API major version, e.g. {@code 1.x}
 * @param vendor who publishes the plugin
 * @param license license of the plugin
 * @param description one sentence describing the plugin
 * @param provides the components of the plugin
 * @param datasource data source type and verified versions, if the plugin connects to one
 * @param drivers driver plugins the plugin needs
 * @param extra further fields kept as written (lob, state-downgrade, skipped-testkit-groups, ...)
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String apiVersion,
        @Nullable String vendor,
        @Nullable String license,
        @Nullable String description,
        List<Component> provides,
        @Nullable Datasource datasource,
        List<String> drivers,
        Map<String, Object> extra) {

    /** File name of the descriptor at the root of a plugin package. */
    public static final String FILE_NAME = "plugin.yaml";

    private static final Pattern ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?");
    private static final Pattern API_VERSION = Pattern.compile("(\\d+)\\.x");
    private static final Pattern CLASS_NAME = Pattern.compile("[a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)+");
    private static final Set<String> KNOWN = Set.of(
            "id",
            "name",
            "version",
            "api-version",
            "vendor",
            "license",
            "description",
            "provides",
            "datasource",
            "requires");

    /** One provided component. */
    public record Component(PluginType type, String identifier, String className) {}

    /** The data source a plugin connects to and the versions verified end to end. */
    public record Datasource(String type, List<String> versions) {
        public Datasource {
            versions = List.copyOf(versions);
        }
    }

    public PluginDescriptor {
        provides = List.copyOf(provides);
        drivers = List.copyOf(drivers);
        extra = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    /** The API major version, e.g. 1 for {@code 1.x}. */
    public int apiMajorVersion() {
        var matcher = API_VERSION.matcher(apiVersion);
        if (!matcher.matches()) {
            throw new IllegalStateException("Malformed api-version: " + apiVersion);
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** {@code id@version}. */
    public String coordinate() {
        return id + "@" + version;
    }

    /** Result of parsing: the descriptor when valid, and the problems found. */
    public record ParseResult(@Nullable PluginDescriptor descriptor, List<String> problems) {
        public ParseResult {
            problems = List.copyOf(problems);
        }

        public boolean isValid() {
            return descriptor != null && problems.isEmpty();
        }

        /** The descriptor, or an exception listing every problem. */
        public PluginDescriptor orElseThrow() {
            if (!isValid()) {
                throw new IllegalArgumentException("Invalid plugin.yaml: " + String.join("; ", problems));
            }
            return descriptor;
        }
    }

    /** Parses and validates the text of a {@code plugin.yaml}. */
    public static ParseResult parse(String text) {
        List<String> problems = new ArrayList<>();
        Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (YAMLException e) {
            return new ParseResult(null, List.of("not valid YAML: " + e.getMessage()));
        }
        if (!(document instanceof Map<?, ?> map)) {
            return new ParseResult(null, List.of("plugin.yaml must be a mapping"));
        }
        String id = text(map, "id", problems, true);
        if (id != null && !ID.matcher(id).matches()) {
            problems.add("id must be lower case words joined by '-': " + id);
        }
        String name = text(map, "name", problems, true);
        String version = text(map, "version", problems, true);
        if (version != null && !VERSION.matcher(version).matches()) {
            problems.add("version must be a semantic version such as 1.0.0: " + version);
        }
        String apiVersion = text(map, "api-version", problems, true);
        if (apiVersion != null && !API_VERSION.matcher(apiVersion).matches()) {
            problems.add("api-version must name an API major version such as \"1.x\": " + apiVersion);
        }
        List<Component> provides = components(map.get("provides"), problems);
        Datasource datasource = datasource(map.get("datasource"), problems);
        List<String> drivers = drivers(map.get("requires"), problems);
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!KNOWN.contains(key)) {
                extra.put(key, entry.getValue());
            }
        }
        if (!problems.isEmpty() || id == null || name == null || version == null || apiVersion == null) {
            return new ParseResult(null, problems);
        }
        return new ParseResult(
                new PluginDescriptor(
                        id,
                        name,
                        version,
                        apiVersion,
                        text(map, "vendor", problems, false),
                        text(map, "license", problems, false),
                        text(map, "description", problems, false),
                        provides,
                        datasource,
                        drivers,
                        extra),
                problems);
    }

    private static @Nullable String text(Map<?, ?> map, String key, List<String> problems, boolean required) {
        Object value = map.get(key);
        if (value == null) {
            if (required) {
                problems.add(key + " is required");
            }
            return null;
        }
        if (value instanceof Map || value instanceof List) {
            problems.add(key + " must be a plain value");
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() && required) {
            problems.add(key + " must not be empty");
            return null;
        }
        return text;
    }

    private static List<Component> components(@Nullable Object value, List<String> problems) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            problems.add("provides must list at least one component");
            return List.of();
        }
        List<Component> components = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (Object item : list) {
            index++;
            String where = "provides[" + index + "]";
            if (!(item instanceof Map<?, ?> entry)) {
                problems.add(where + " must be a mapping with type, identifier and class");
                continue;
            }
            String typeText = text(entry, "type", problems, true);
            String identifier = text(entry, "identifier", problems, true);
            String className = text(entry, "class", problems, true);
            PluginType type =
                    typeText == null ? null : PluginType.parse(typeText).orElse(null);
            if (typeText != null && type == null) {
                problems.add(where + " has unknown type '" + typeText + "'");
            }
            if (identifier != null && !ID.matcher(identifier).matches()) {
                problems.add(where + " identifier must be lower case words joined by '-': " + identifier);
            }
            if (className != null && !CLASS_NAME.matcher(className).matches()) {
                problems.add(where + " class is not a fully qualified class name: " + className);
            }
            if (type != null && identifier != null && className != null) {
                if (!seen.add(type.id() + ":" + identifier)) {
                    problems.add(where + " repeats " + type.id() + " '" + identifier + "'");
                }
                components.add(new Component(type, identifier, className));
            }
        }
        return components;
    }

    private static @Nullable Datasource datasource(@Nullable Object value, List<String> problems) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            problems.add("datasource must be a mapping");
            return null;
        }
        String type = text(map, "type", problems, true);
        List<String> versions = new ArrayList<>();
        Object list = map.get("versions");
        if (list instanceof List<?> items) {
            items.forEach(v -> versions.add(String.valueOf(v)));
        } else if (list != null) {
            problems.add("datasource.versions must be a list");
        }
        return type == null ? null : new Datasource(type, versions);
    }

    private static List<String> drivers(@Nullable Object value, List<String> problems) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            problems.add("requires must be a mapping");
            return List.of();
        }
        Object drivers = map.get("drivers");
        if (drivers == null) {
            return List.of();
        }
        if (!(drivers instanceof List<?> list)) {
            problems.add("requires.drivers must be a list");
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
