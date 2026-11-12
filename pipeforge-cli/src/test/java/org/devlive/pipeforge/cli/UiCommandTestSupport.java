/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.devlive.pipeforge.cli.command.UiCommand;
import picocli.CommandLine;

/** Reaches the package-private parts of {@link UiCommand} for its test. */
final class UiCommandTestSupport {

    private UiCommandTestSupport() {}

    static Path find(Path directory) {
        return UiCommand.find(directory)
                .orElseThrow(() -> new IllegalStateException("no server jar in " + directory.toAbsolutePath()));
    }

    static boolean answers(int port) {
        return UiCommand.answers(URI.create("http://127.0.0.1:" + port + "/api/v1/version"));
    }

    /** The command that ui would run, for the given global options. */
    static List<String> command(Shell shell, String... options) {
        Environment environment = shell.environment(
                new java.io.PrintStream(
                        new java.io.ByteArrayOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8),
                new java.io.PrintStream(
                        new java.io.ByteArrayOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8));
        CommandLine commandLine = Cli.commandLine(environment, "en-US");
        String[] args = new String[options.length + 1];
        System.arraycopy(options, 0, args, 0, options.length);
        args[options.length] = "ui";
        CommandLine.ParseResult parsed = commandLine.parseArgs(args);
        Root root = (Root) parsed.commandSpec().userObject();
        UiCommand ui = (UiCommand) parsed.subcommand().commandSpec().userObject();
        try (Session session = new Session(root, environment)) {
            return ui.command(session, Path.of("server.jar"));
        }
    }
}
