/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * The error registry: every error code with its kind and its localized texts.
 *
 * <p>Core codes live in {@code errors/<category>.yaml} of this module; plugins register their
 * codes in {@code META-INF/pipeforge/errors.yaml}. Loading validates every entry (see {@link
 * #problems()}); the build fails when the core registry has problems or when it disagrees with
 * the {@link ErrorCode} constants.
 */
public final class ErrorRegistry {

    /** Resource that plugins use to register their own codes. */
    public static final String PLUGIN_RESOURCE = "META-INF/pipeforge/errors.yaml";

    private static final Set<String> KEYS = Set.of("code", "name", "kind", "message", "hint", "deprecated", "docs");

    private static volatile @Nullable ErrorRegistry global;

    private final Map<String, ErrorDefinition> byCode;
    private final List<String> problems;

    private ErrorRegistry(Map<String, ErrorDefinition> byCode, List<String> problems) {
        this.byCode = Collections.unmodifiableMap(byCode);
        this.problems = List.copyOf(problems);
    }

    /**
     * The registry used to render messages: the core registry plus the codes of every plugin
     * class loader added with {@link #include(ClassLoader)}.
     */
    public static ErrorRegistry global() {
        ErrorRegistry registry = global;
        if (registry == null) {
            synchronized (ErrorRegistry.class) {
                registry = global;
                if (registry == null) {
                    registry = load(ErrorRegistry.class.getClassLoader());
                    global = registry;
                }
            }
        }
        return registry;
    }

    /** Adds the codes that a plugin class loader registers to the global registry. */
    public static synchronized ErrorRegistry include(ClassLoader pluginLoader) {
        ErrorRegistry current = global();
        Map<String, String> sources = new LinkedHashMap<>();
        readPluginResources(pluginLoader, sources, new ArrayList<>());
        ErrorRegistry plugins = parse(sources);
        Map<String, ErrorDefinition> merged = new LinkedHashMap<>(current.byCode);
        List<String> problems = new ArrayList<>(current.problems);
        problems.addAll(plugins.problems);
        for (ErrorDefinition definition : plugins.all()) {
            ErrorDefinition existing = merged.putIfAbsent(definition.code(), definition);
            if (existing != null && !existing.source().equals(definition.source())) {
                problems.add(definition.source() + ": " + definition.code() + " is already registered by "
                        + existing.source());
            }
        }
        ErrorRegistry registry = new ErrorRegistry(merged, problems);
        global = registry;
        return registry;
    }

    /** Loads the core registry and every plugin registry visible to {@code loader}. */
    public static ErrorRegistry load(ClassLoader loader) {
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (ErrorCategory category : ErrorCategory.values()) {
            String resource = category.registryResource();
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in != null) {
                    sources.put(resource, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                problems.add(resource + ": cannot be read: " + e.getMessage());
            }
        }
        readPluginResources(loader, sources, problems);
        ErrorRegistry registry = parse(sources);
        List<String> all = new ArrayList<>(problems);
        all.addAll(registry.problems);
        return new ErrorRegistry(new LinkedHashMap<>(registry.byCode), all);
    }

    private static void readPluginResources(ClassLoader loader, Map<String, String> sources, List<String> problems) {
        try {
            Enumeration<URL> urls = loader.getResources(PLUGIN_RESOURCE);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try (InputStream in = url.openStream()) {
                    sources.put(url.toString(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    problems.add(url + ": cannot be read: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problems.add(PLUGIN_RESOURCE + ": cannot be listed: " + e.getMessage());
        }
    }

    /**
     * Parses and validates registry files.
     *
     * @param sources registry text by source name; a core file is named {@code
     *     errors/<category>.yaml} and may only contain codes of that category
     */
    public static ErrorRegistry parse(Map<String, String> sources) {
        Map<String, ErrorDefinition> byCode = new LinkedHashMap<>();
        Map<String, String> nameOwners = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            for (ErrorDefinition definition : parseSource(source.getKey(), source.getValue(), problems)) {
                if (byCode.containsKey(definition.code())) {
                    problems.add(source.getKey() + ": duplicate code " + definition.code());
                    continue;
                }
                String owner = nameOwners.putIfAbsent(definition.name(), definition.code());
                if (owner != null) {
                    problems.add(source.getKey() + ": name " + definition.name() + " is used by " + owner + " and "
                            + definition.code());
                }
                byCode.put(definition.code(), definition);
            }
        }
        return new ErrorRegistry(byCode, problems);
    }

    private static List<ErrorDefinition> parseSource(String source, String text, List<String> problems) {
        Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (YAMLException e) {
            problems.add(source + ": not valid YAML: " + e.getMessage());
            return List.of();
        }
        if (document == null) {
            return List.of();
        }
        if (!(document instanceof List<?> entries)) {
            problems.add(source + ": must be a list of error entries");
            return List.of();
        }
        @Nullable ErrorCategory fileCategory = coreCategory(source);
        List<ErrorDefinition> definitions = new ArrayList<>();
        int index = 0;
        for (Object entry : entries) {
            index++;
            String where = source + " entry " + index;
            if (!(entry instanceof Map<?, ?> map)) {
                problems.add(where + ": must be a mapping");
                continue;
            }
            ErrorDefinition definition = parseEntry(where, source, map, fileCategory, problems);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        return definitions;
    }

    private static @Nullable ErrorCategory coreCategory(String source) {
        for (ErrorCategory category : ErrorCategory.values()) {
            if (source.equals(category.registryResource())) {
                return category;
            }
        }
        return null;
    }

    private static @Nullable ErrorDefinition parseEntry(
            String where, String source, Map<?, ?> map, @Nullable ErrorCategory fileCategory, List<String> problems) {
        int before = problems.size();
        for (Object key : map.keySet()) {
            if (!KEYS.contains(String.valueOf(key))) {
                problems.add(where + ": unknown key '" + key + "'");
            }
        }
        String code = string(map.get("code"));
        String name = string(map.get("name"));
        if (code == null || !ErrorCode.FORMAT.matcher(code).matches()) {
            problems.add(where + ": code must look like PF-CONF-0001, got '" + code + "'");
            return null;
        }
        where = source + " " + code;
        ErrorCategory category;
        Matcher parts = ErrorCode.FORMAT.matcher(code);
        try {
            category = ErrorCode.categoryOf(parts.matches() ? parts.group(1) : "", code);
        } catch (IllegalArgumentException e) {
            problems.add(where + ": unknown category");
            return null;
        }
        if (fileCategory != null && fileCategory != category) {
            problems.add(where + ": belongs in " + category.registryResource());
        }
        if (name == null || !name.matches("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*")) {
            problems.add(where + ": name must be upper snake case, got '" + name + "'");
        }
        ErrorKind kind = category.defaultKind();
        String kindText = string(map.get("kind"));
        if (kindText != null) {
            try {
                kind = ErrorKind.valueOf(kindText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add(where + ": unknown kind '" + kindText + "'");
            }
        }
        Map<String, String> message = texts(where, "message", map.get("message"), problems);
        Map<String, String> hint = texts(where, "hint", map.get("hint"), problems);
        checkPlaceholders(where, message, hint, problems);
        Object deprecated = map.get("deprecated");
        if (deprecated != null && !(deprecated instanceof Boolean)) {
            problems.add(where + ": deprecated must be true or false");
        }
        String docs = string(map.get("docs"));
        if (problems.size() > before) {
            return null;
        }
        return new ErrorDefinition(
                code,
                Objects.requireNonNull(name),
                kind,
                message,
                hint,
                Boolean.TRUE.equals(deprecated),
                docs != null ? docs : "reference/errors#" + code.toLowerCase(Locale.ROOT),
                source);
    }

    private static Map<String, String> texts(String where, String key, @Nullable Object value, List<String> problems) {
        Map<String, String> texts = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            problems.add(where + ": " + key + " must map languages to texts");
            return texts;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String language = String.valueOf(entry.getKey());
            String text = string(entry.getValue());
            if (!ErrorDefinition.LANGUAGES.contains(language)) {
                problems.add(where + ": " + key + " has unsupported language '" + language + "'");
            } else if (text == null || text.isBlank()) {
                problems.add(where + ": " + key + " in " + language + " is empty");
            } else {
                texts.put(language, text.strip());
            }
        }
        for (String language : ErrorDefinition.LANGUAGES) {
            if (!map.containsKey(language)) {
                problems.add(where + ": " + key + " lacks " + language);
            }
        }
        return texts;
    }

    private static void checkPlaceholders(
            String where, Map<String, String> message, Map<String, String> hint, List<String> problems) {
        Set<String> english = MessageTemplate.placeholders(message.getOrDefault(ErrorDefinition.DEFAULT_LANGUAGE, ""));
        for (Map.Entry<String, String> text : message.entrySet()) {
            Set<String> names = MessageTemplate.placeholders(text.getValue());
            if (!new HashSet<>(names).equals(new HashSet<>(english))) {
                problems.add(where + ": message in " + text.getKey() + " uses " + names + " but en-US uses " + english);
            }
        }
        for (Map.Entry<String, String> text : hint.entrySet()) {
            Set<String> names = MessageTemplate.placeholders(text.getValue());
            if (!english.containsAll(names)) {
                problems.add(where + ": hint in " + text.getKey() + " uses parameters " + names
                        + " that the message does not have");
            }
        }
    }

    private static @Nullable String string(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Problems found while loading; empty for a valid registry. */
    public List<String> problems() {
        return problems;
    }

    public Optional<ErrorDefinition> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public Optional<ErrorDefinition> find(ErrorCode code) {
        return find(code.id());
    }

    /** Every definition, in registry order. */
    public List<ErrorDefinition> all() {
        return List.copyOf(byCode.values());
    }

    /**
     * Compares error code constants with the registry: every code must be registered under the
     * same name, and its declared parameters must be exactly the placeholders of its message.
     */
    public List<String> checkCodes(Collection<ErrorCode> codes) {
        List<String> mismatches = new ArrayList<>();
        for (ErrorCode code : codes) {
            ErrorDefinition definition = byCode.get(code.id());
            if (definition == null) {
                mismatches.add(code.id() + " (" + code.name() + ") is not registered");
                continue;
            }
            if (!definition.name().equals(code.name())) {
                mismatches.add(
                        code.id() + " is registered as " + definition.name() + " but declared as " + code.name());
            }
            if (!new HashSet<>(code.params()).equals(definition.params())) {
                mismatches.add(code.id() + " declares parameters " + code.params() + " but its message uses "
                        + definition.params());
            }
        }
        return mismatches;
    }

    /** The {@link ErrorCode} constants declared by {@code holder} (public static final fields). */
    public static List<ErrorCode> constantsOf(Class<?> holder) {
        List<ErrorCode> codes = new ArrayList<>();
        for (Field field : holder.getFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == ErrorCode.class) {
                try {
                    codes.add((ErrorCode) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read " + field, e);
                }
            }
        }
        return codes;
    }
}
