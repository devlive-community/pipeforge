/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.devlive.pipeforge.api.option.OptionViolation;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.devlive.pipeforge.core.variable.VariableResolver;
import org.jspecify.annotations.Nullable;

/**
 * Reads a job written in YAML or JSON, resolves its variables and checks its structure.
 *
 * <pre>
 * version: 1
 * name: orders-to-console
 * env:
 *   parallelism: 2
 *   variables: { biz_date: "${date:yyyy-MM-dd,-1d}" }
 * source:
 *   - { id: src, type: fake, rows: 100 }
 * sink:
 *   - { id: out, type: console, inputs: [src] }
 * </pre>
 *
 * <p>Structural problems throw {@link org.devlive.pipeforge.common.error.ErrorCode#JOB_INVALID}
 * with the line, column and path; invalid env options throw the option errors; undefined
 * variables throw {@link org.devlive.pipeforge.common.error.ErrorCode#VARIABLE_UNDEFINED}. Plugin
 * options and the connections between nodes are checked by the planner.
 */
public final class JobParser {

    /** The only DSL version. */
    public static final int VERSION = 1;

    static final List<String> FIELDS = List.of("version", "name", "description", "env", "source", "transform", "sink");
    static final List<String> NODE_FIELDS = List.of("id", "type", "plugin", "inputs", "parallelism");

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");
    static final Pattern TYPE = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    static final Pattern PLUGIN =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*(?:@\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?)?");
    private static final String VARIABLES = "variables";

    private JobParser() {}

    /** Parses a job; see the class documentation for the errors. */
    public static JobDefinition parse(String text, VariableContext context) {
        Tree.Mapping root =
                mapping(YamlReader.read(text), "a job is a mapping with the fields " + String.join(", ", FIELDS));
        checkKeys(root, FIELDS);
        int version = version(root);
        Tree.@Nullable Mapping rawEnv = optionalMapping(root.entries().get("env"));
        Map<String, String> definitions = variableDefinitions(rawEnv);

        ZoneId zone = context.defaultTimeZone();
        Tree zoneTree = rawEnv == null ? null : rawEnv.entries().get(EnvOptions.TIMEZONE.key());
        if (zoneTree != null) {
            String zoneText = requiredText(
                    resolve(zoneTree, new VariableResolver(definitions, context, null, null)), "the time zone");
            try {
                zone = ZoneId.of(zoneText);
            } catch (DateTimeException e) {
                throw YamlReader.invalid(zoneTree.location(), "unknown time zone '" + zoneText + "'");
            }
        }

        Tree nameTree = root.entries().get("name");
        if (nameTree == null) {
            throw YamlReader.invalid(root.location(), "the job has no name");
        }
        String name =
                requiredText(resolve(nameTree, new VariableResolver(definitions, context, zone, null)), "the name");
        if (!NAME.matcher(name).matches()) {
            throw YamlReader.invalid(
                    nameTree.location(),
                    "invalid job name '" + name
                            + "': use up to 128 letters, digits, '_', '.' and '-', starting with a letter or digit");
        }

        VariableResolver resolver = new VariableResolver(definitions, context, zone, name);
        // Resolving the tree first reports problems in env.variables at the definition itself.
        Tree.Mapping job = (Tree.Mapping) resolve(root, resolver);
        Map<String, String> variables;
        try {
            variables = resolver.variables();
        } catch (IllegalArgumentException e) {
            throw YamlReader.invalid(Location.of("the variables passed to the run"), String.valueOf(e.getMessage()));
        }
        Tree descriptionTree = job.entries().get("description");
        String description = descriptionTree == null ? null : requiredText(descriptionTree, "the description");
        ReadonlyConfig env = env(optionalMapping(job.entries().get("env")));
        return new JobDefinition(version, name, description, zone, variables, env, nodes(job));
    }

    // ------------------------------------------------------------------ fields

    private static int version(Tree.Mapping root) {
        Tree tree = root.entries().get("version");
        if (tree == null) {
            throw YamlReader.invalid(root.location(), "the job has no version; write 'version: " + VERSION + "'");
        }
        Object value = tree instanceof Tree.Scalar scalar ? scalar.value() : null;
        if (!(value instanceof Long number && number == VERSION)
                && !String.valueOf(VERSION).equals(value)) {
            throw YamlReader.invalid(
                    tree.location(), "unsupported version '" + tree.toPlain() + "'; the only version is " + VERSION);
        }
        return VERSION;
    }

    private static Map<String, String> variableDefinitions(Tree.@Nullable Mapping env) {
        Map<String, String> definitions = new LinkedHashMap<>();
        Tree tree = env == null ? null : env.entries().get(VARIABLES);
        if (tree == null) {
            return definitions;
        }
        Tree.Mapping mapping = mapping(tree, "variables is a mapping of names to values");
        mapping.entries().forEach((key, value) -> {
            if (!(value instanceof Tree.Scalar scalar) || scalar.value() == null) {
                throw YamlReader.invalid(
                        value.location(), "the value of variable '" + key + "' must be a text or number");
            }
            definitions.put(key, String.valueOf(scalar.value()));
        });
        return definitions;
    }

    private static ReadonlyConfig env(Tree.@Nullable Mapping env) {
        if (env == null) {
            return ReadonlyConfig.empty();
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        Map<String, Location> locations = new LinkedHashMap<>();
        env.entries().forEach((key, value) -> {
            if (!key.equals(VARIABLES)) {
                flatten(key, value, flat, locations);
            }
        });
        for (Map.Entry<String, Location> entry : locations.entrySet()) {
            String key = entry.getKey();
            for (String prefix : EnvOptions.CLUSTER_PREFIXES) {
                if (key.equals(prefix) || key.startsWith(prefix + ".")) {
                    throw YamlReader.invalid(
                            entry.getValue(),
                            "'" + key + "' is cluster configuration; set it in the configuration file, not in a job");
                }
            }
        }
        ReadonlyConfig config = ReadonlyConfig.of(flat);
        List<OptionViolation> violations = EnvOptions.RULE.validate(config, "env");
        if (!violations.isEmpty()) {
            throw violations.get(0).toException();
        }
        return config;
    }

    private static List<NodeDefinition> nodes(Tree.Mapping job) {
        List<NodeDefinition> nodes = new ArrayList<>();
        Map<String, Location> ids = new LinkedHashMap<>();
        String lastProducer = null;
        for (NodeKind kind : NodeKind.values()) {
            Tree tree = job.entries().get(kind.id());
            List<Tree> items = new ArrayList<>();
            if (tree instanceof Tree.Sequence sequence) {
                items.addAll(sequence.items());
            } else if (tree instanceof Tree.Mapping) {
                items.add(tree);
            } else if (tree != null && !(tree instanceof Tree.Scalar scalar && scalar.value() == null)) {
                throw YamlReader.invalid(tree.location(), kind.id() + " is a list of nodes");
            }
            if (items.isEmpty() && kind != NodeKind.TRANSFORM) {
                throw YamlReader.invalid(
                        tree == null ? job.location().child(kind.id()) : tree.location(),
                        "a job needs at least one " + kind.id());
            }
            for (Tree item : items) {
                NodeDefinition node =
                        node(kind, mapping(item, "a node is a mapping with id, type and its options"), lastProducer);
                Location previous = ids.putIfAbsent(node.id(), node.location());
                if (previous != null) {
                    throw YamlReader.invalid(
                            node.location().child("id"),
                            "the node id '" + node.id() + "' is already used"
                                    + (previous.line() > 0 ? " at line " + previous.line() : ""));
                }
                nodes.add(node);
                if (kind != NodeKind.SINK) {
                    lastProducer = node.id();
                }
            }
        }
        return nodes;
    }

    private static NodeDefinition node(NodeKind kind, Tree.Mapping node, @Nullable String lastProducer) {
        Map<String, Tree> fields = node.entries();
        String id = requiredField(node, "id");
        if (!ID.matcher(id).matches()) {
            throw YamlReader.invalid(
                    fields.get("id").location(),
                    "invalid node id '" + id
                            + "': use up to 64 letters, digits, '_' and '-', starting with a letter or '_'");
        }
        String type = requiredField(node, "type");
        if (!TYPE.matcher(type).matches()) {
            throw YamlReader.invalid(
                    fields.get("type").location(),
                    "invalid type '" + type + "': component types are lower-case words joined by '-', e.g. mysql-cdc");
        }
        String plugin = null;
        if (fields.get("plugin") != null) {
            plugin = requiredText(fields.get("plugin"), "plugin");
            if (!PLUGIN.matcher(plugin).matches()) {
                throw YamlReader.invalid(
                        fields.get("plugin").location(),
                        "invalid plugin '" + plugin
                                + "': write the plugin id, optionally with a version, e.g. jdbc@1.2.0");
            }
        }
        List<String> inputs = new ArrayList<>();
        boolean implied = false;
        Tree inputsTree = fields.get("inputs");
        if (kind == NodeKind.SOURCE) {
            if (inputsTree != null) {
                throw YamlReader.invalid(inputsTree.location(), "sources have no inputs");
            }
        } else if (inputsTree == null) {
            implied = true;
            if (lastProducer != null) {
                inputs.add(lastProducer);
            }
        } else if (inputsTree instanceof Tree.Sequence sequence) {
            for (Tree item : sequence.items()) {
                inputs.add(requiredText(item, "an input"));
            }
            if (inputs.isEmpty()) {
                throw YamlReader.invalid(inputsTree.location(), "inputs must name at least one node");
            }
        } else {
            inputs.add(requiredText(inputsTree, "inputs"));
        }
        Integer parallelism = null;
        Tree parallelismTree = fields.get("parallelism");
        if (parallelismTree != null) {
            parallelism = parallelism(parallelismTree);
        }
        Map<String, Object> options = new LinkedHashMap<>();
        Map<String, Location> locations = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (!NODE_FIELDS.contains(key)) {
                flatten(key, value, options, locations);
            }
        });
        return new NodeDefinition(id, kind, type, plugin, inputs, implied, parallelism, options, node.location());
    }

    private static int parallelism(Tree tree) {
        Object value = tree instanceof Tree.Scalar scalar ? scalar.value() : null;
        long number;
        if (value instanceof Long l) {
            number = l;
        } else {
            try {
                number = Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw YamlReader.invalid(
                        tree.location(), "parallelism must be a whole number, got '" + tree.toPlain() + "'");
            }
        }
        if (number < 1 || number > EnvOptions.MAX_PARALLELISM) {
            throw YamlReader.invalid(
                    tree.location(),
                    "parallelism must be between 1 and " + EnvOptions.MAX_PARALLELISM + ", got " + number);
        }
        return (int) number;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Writes a value under dotted keys: {@code split: {strategy: range}} becomes {@code
     * split.strategy: range}. Lists are kept as lists.
     */
    private static void flatten(String key, Tree value, Map<String, Object> out, Map<String, Location> locations) {
        if (value instanceof Tree.Mapping mapping && !mapping.entries().isEmpty()) {
            mapping.entries().forEach((child, tree) -> flatten(key + "." + child, tree, out, locations));
            return;
        }
        if (out.containsKey(key)) {
            throw YamlReader.invalid(value.location(), "'" + key + "' is written twice");
        }
        out.put(key, value.toPlain());
        locations.put(key, value.location());
    }

    private static Tree resolve(Tree tree, VariableResolver resolver) {
        if (tree instanceof Tree.Scalar scalar) {
            if (scalar.value() instanceof String text && text.contains("${")) {
                try {
                    return new Tree.Scalar(resolver.resolve(text), scalar.location());
                } catch (IllegalArgumentException e) {
                    throw YamlReader.invalid(scalar.location(), String.valueOf(e.getMessage()));
                }
            }
            return scalar;
        }
        if (tree instanceof Tree.Mapping mapping) {
            Map<String, Tree> entries = new LinkedHashMap<>();
            mapping.entries().forEach((key, value) -> entries.put(key, resolve(value, resolver)));
            return new Tree.Mapping(entries, mapping.location());
        }
        Tree.Sequence sequence = (Tree.Sequence) tree;
        return new Tree.Sequence(
                sequence.items().stream().map(item -> resolve(item, resolver)).toList(), sequence.location());
    }

    private static void checkKeys(Tree.Mapping mapping, List<String> known) {
        for (Map.Entry<String, Tree> entry : mapping.entries().entrySet()) {
            if (!known.contains(entry.getKey())) {
                throw YamlReader.invalid(
                        entry.getValue().location(),
                        Suggestions.withSuggestion("unknown field '" + entry.getKey() + "'", entry.getKey(), known));
            }
        }
    }

    private static Tree.Mapping mapping(Tree tree, String expectation) {
        if (tree instanceof Tree.Mapping mapping) {
            return mapping;
        }
        throw YamlReader.invalid(tree.location(), expectation);
    }

    private static Tree.@Nullable Mapping optionalMapping(@Nullable Tree tree) {
        if (tree == null || (tree instanceof Tree.Scalar scalar && scalar.value() == null)) {
            return null;
        }
        return mapping(tree, tree.location().path() + " is a mapping");
    }

    private static String requiredField(Tree.Mapping node, String field) {
        Tree tree = node.entries().get(field);
        if (tree == null) {
            throw YamlReader.invalid(node.location(), "the node has no " + field);
        }
        return requiredText(tree, field);
    }

    private static String requiredText(Tree tree, String what) {
        if (tree instanceof Tree.Scalar scalar && scalar.value() != null) {
            String text = String.valueOf(scalar.value());
            if (!text.isBlank()) {
                return text;
            }
        }
        throw YamlReader.invalid(tree.location(), what + " must be a non-empty text");
    }
}
