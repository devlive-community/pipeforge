/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import picocli.CommandLine;

/** Parses the arguments and runs the command; returns the exit code. */
public final class Cli {

    private final Environment environment;

    public Cli(Environment environment) {
        this.environment = environment;
    }

    public int run(String... args) {
        return commandLine(args).execute(args);
    }

    /** The command line, with the help texts in the language the arguments or the environment ask for. */
    public CommandLine commandLine(String... args) {
        String language =
                Messages.resolve(argument(args, "--lang"), environment.variable("PIPEFORGE_LANG"), Locale.getDefault());
        CommandLine commandLine = commandLine(environment, language);
        Messages messages = Messages.of(language);
        commandLine.setParameterExceptionHandler((ex, arguments) -> {
            PrintWriter err = ex.getCommandLine().getErr();
            err.println(messages.get("usage.error", ex.getMessage()));
            CommandLine.UnmatchedArgumentException.printSuggestions(ex, err);
            err.println(messages.get(
                    "usage.help", ex.getCommandLine().getCommandSpec().qualifiedName()));
            err.flush();
            return ExitCode.USAGE.code();
        });
        return commandLine;
    }

    /** The command tree with its texts in {@code language}; also used to generate the reference. */
    public static CommandLine commandLine(Environment environment, String language) {
        CommandLine commandLine = new CommandLine(new Root(), new Factory(environment));
        commandLine.setResourceBundle(Messages.bundle(language));
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setOut(
                new PrintWriter(new java.io.OutputStreamWriter(environment.out(), StandardCharsets.UTF_8), true));
        commandLine.setErr(
                new PrintWriter(new java.io.OutputStreamWriter(environment.err(), StandardCharsets.UTF_8), true));
        return commandLine;
    }

    /** The value of an option before the arguments are parsed, e.g. --lang zh-CN or --lang=zh-CN. */
    static @org.jspecify.annotations.Nullable String argument(String[] args, String name) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(name) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (args[i].startsWith(name + "=")) {
                return args[i].substring(name.length() + 1);
            }
        }
        return null;
    }

    /** Creates commands and hands them the environment. */
    private record Factory(Environment environment) implements CommandLine.IFactory {
        @Override
        public <K> K create(Class<K> type) throws Exception {
            K object = CommandLine.defaultFactory().create(type);
            if (object instanceof BaseCommand command) {
                command.environment(environment);
            }
            return object;
        }
    }
}
