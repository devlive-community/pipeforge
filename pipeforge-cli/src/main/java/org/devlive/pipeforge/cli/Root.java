/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.cli.command.CompletionCommand;
import org.devlive.pipeforge.cli.command.DoctorCommand;
import org.devlive.pipeforge.cli.command.ExplainCommand;
import org.devlive.pipeforge.cli.command.ExprCommand;
import org.devlive.pipeforge.cli.command.RunCommand;
import org.devlive.pipeforge.cli.command.UiCommand;
import org.devlive.pipeforge.cli.command.ValidateCommand;
import org.devlive.pipeforge.cli.command.VersionCommand;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/** {@code pipeforge}: the global options, inherited by every command. */
@Command(
        name = "pipeforge",
        resourceBundle = Messages.BUNDLE,
        mixinStandardHelpOptions = true,
        versionProvider = VersionCommand.Provider.class,
        sortOptions = false,
        usageHelpAutoWidth = true,
        subcommands = {
            ValidateCommand.class,
            ExplainCommand.class,
            RunCommand.class,
            ExprCommand.class,
            UiCommand.class,
            VersionCommand.class,
            DoctorCommand.class,
            CompletionCommand.class
        })
public final class Root implements java.util.concurrent.Callable<Integer> {

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    /** Without a command: the usage on the standard error, exit code 2. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return ExitCode.USAGE.code();
    }

    @Option(
            names = {"-o", "--output"},
            paramLabel = "<format>",
            descriptionKey = "option.output",
            scope = ScopeType.INHERIT,
            defaultValue = "table",
            converter = FormatConverter.class)
    Output.Format output = Output.Format.TABLE;

    @Option(names = "--lang", paramLabel = "<language>", descriptionKey = "option.lang", scope = ScopeType.INHERIT)
    @Nullable
    String lang;

    @Option(names = "--no-color", descriptionKey = "option.no-color", scope = ScopeType.INHERIT)
    boolean noColor;

    @Option(
            names = {"-q", "--quiet"},
            descriptionKey = "option.quiet",
            scope = ScopeType.INHERIT)
    boolean quiet;

    @Option(
            names = {"-v", "--verbose"},
            descriptionKey = "option.verbose",
            scope = ScopeType.INHERIT)
    boolean verbose;

    @Option(
            names = "--plugin-path",
            paramLabel = "<dir>",
            descriptionKey = "option.plugin-path",
            scope = ScopeType.INHERIT)
    List<Path> pluginPaths = new ArrayList<>();

    @Option(
            names = "--dev",
            paramLabel = "<root>",
            arity = "0..1",
            fallbackValue = "",
            descriptionKey = "option.dev",
            scope = ScopeType.INHERIT)
    @Nullable
    String dev;

    /** Accepts table, json and yaml in any case. */
    static final class FormatConverter implements picocli.CommandLine.ITypeConverter<Output.Format> {
        @Override
        public Output.Format convert(String value) {
            for (Output.Format format : Output.Format.values()) {
                if (format.name().equalsIgnoreCase(value)) {
                    return format;
                }
            }
            throw new picocli.CommandLine.TypeConversionException("'" + value + "' is not one of table, json, yaml");
        }
    }
}
