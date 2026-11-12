/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * The command reference of the documentation site, read from the command definitions in both
 * languages: commands with their synopsis, description, options and arguments, the global options
 * and the exit codes. A test keeps {@code pipeforge-cli/cli.json} equal to it.
 */
public final class CliReference {

    private CliReference() {}

    /** The reference as a JSON-ready tree. */
    public static Map<String, Object> render() {
        Map<String, CommandSpec> roots = new LinkedHashMap<>();
        for (String language : Messages.LANGUAGES) {
            roots.put(language, Cli.commandLine(Environment.system(), language).getCommandSpec());
        }
        CommandSpec root = roots.get("en-US");
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("name", root.name());
        reference.put("description", texts(roots, CliReference::description));
        reference.put("globalOptions", options(roots, List.of()));
        List<Object> commands = new ArrayList<>();
        for (CommandLine sub : root.subcommands().values()) {
            commands(roots, List.of(sub.getCommandName()), commands);
        }
        reference.put("commands", commands);
        List<Object> exits = new ArrayList<>();
        for (ExitCode code : ExitCode.values()) {
            Map<String, Object> exit = new LinkedHashMap<>();
            exit.put("code", code.code());
            Map<String, String> description = new LinkedHashMap<>();
            for (String language : Messages.LANGUAGES) {
                description.put(language, Messages.of(language).get(code.messageKey()));
            }
            exit.put("description", description);
            exits.add(exit);
        }
        reference.put("exitCodes", exits);
        return reference;
    }

    private static void commands(Map<String, CommandSpec> roots, List<String> path, List<Object> out) {
        Map<String, CommandSpec> specs = new LinkedHashMap<>();
        roots.forEach((language, root) -> specs.put(language, find(root, path)));
        CommandSpec spec = specs.get("en-US");
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("name", String.join(" ", path));
        command.put("anchor", "cmd-" + String.join("-", path));
        command.put("synopsis", synopsis(spec));
        command.put("description", texts(specs, CliReference::description));
        command.put("arguments", arguments(specs));
        command.put("options", options(specs, spec.root().options()));
        out.add(command);
        for (CommandLine sub : spec.subcommands().values()) {
            List<String> child = new ArrayList<>(path);
            child.add(sub.getCommandName());
            commands(roots, child, out);
        }
    }

    private static CommandSpec find(CommandSpec root, List<String> path) {
        CommandSpec spec = root;
        for (String name : path) {
            spec = spec.subcommands().get(name).getCommandSpec();
        }
        return spec;
    }

    private static String description(CommandSpec spec) {
        return String.join(" ", spec.usageMessage().description()).replace("%n", "\n");
    }

    private static Map<String, String> texts(
            Map<String, CommandSpec> specs, java.util.function.Function<CommandSpec, String> text) {
        Map<String, String> texts = new LinkedHashMap<>();
        specs.forEach((language, spec) -> texts.put(language, text.apply(spec)));
        return texts;
    }

    /** The options of a command, without those it inherits from the root ({@code inherited}) and the help options. */
    private static List<Object> options(Map<String, CommandSpec> specs, List<OptionSpec> inherited) {
        CommandSpec spec = specs.get("en-US");
        List<String> skip = new ArrayList<>();
        inherited.forEach(o -> skip.add(o.longestName()));
        List<Object> options = new ArrayList<>();
        for (OptionSpec option : spec.options()) {
            if (option.hidden() || option.usageHelp() || option.versionHelp() || skip.contains(option.longestName())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("names", List.of(option.names()));
            entry.put("label", option.typeInfo().isBoolean() ? null : label(option));
            entry.put("default", defaultValue(option));
            entry.put("repeatable", option.isMultiValue());
            Map<String, String> description = new LinkedHashMap<>();
            specs.forEach((language, s) -> description.put(language, text(s.findOption(option.longestName()))));
            entry.put("description", description);
            options.add(entry);
        }
        return options;
    }

    private static List<Object> arguments(Map<String, CommandSpec> specs) {
        CommandSpec spec = specs.get("en-US");
        List<Object> arguments = new ArrayList<>();
        for (int i = 0; i < spec.positionalParameters().size(); i++) {
            PositionalParamSpec parameter = spec.positionalParameters().get(i);
            if (parameter.hidden()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", parameter.paramLabel());
            entry.put("repeatable", parameter.isMultiValue());
            entry.put("required", parameter.arity().min > 0);
            Map<String, String> description = new LinkedHashMap<>();
            int index = i;
            specs.forEach((language, s) ->
                    description.put(language, text(s.positionalParameters().get(index))));
            entry.put("description", description);
            arguments.add(entry);
        }
        return arguments;
    }

    private static String label(OptionSpec option) {
        return option.arity().min == 0 ? "[" + option.paramLabel() + "]" : option.paramLabel();
    }

    private static Object defaultValue(OptionSpec option) {
        String value = option.defaultValue();
        return value == null || option.typeInfo().isBoolean() ? null : value;
    }

    private static String text(ArgSpec arg) {
        return String.join(" ", Objects.requireNonNull(arg).description()).replace("%n", "\n");
    }

    private static String synopsis(CommandSpec spec) {
        StringBuilder text = new StringBuilder(spec.qualifiedName());
        if (!spec.subcommands().isEmpty()) {
            text.append(" <command>");
            return text.toString();
        }
        text.append(" [options]");
        for (PositionalParamSpec parameter : spec.positionalParameters()) {
            String label = parameter.paramLabel() + (parameter.isMultiValue() ? "..." : "");
            text.append(' ').append(parameter.arity().min > 0 ? label : "[" + label + "]");
        }
        return text.toString();
    }
}
