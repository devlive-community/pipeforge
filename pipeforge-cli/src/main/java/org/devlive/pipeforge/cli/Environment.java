/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * What the command sees of the machine: streams, environment variables, system properties,
 * directories and how it runs other programs. {@link #system()} is the real one; tests build their
 * own.
 *
 * @param interactive whether the standard streams are a terminal (progress and colours)
 */
public record Environment(
        PrintStream out,
        PrintStream err,
        Map<String, String> variables,
        Map<String, String> properties,
        Path workingDirectory,
        Path userHome,
        boolean interactive,
        ProcessRunner runner,
        Launcher launcher) {

    /** Runs a program and returns its standard output, or empty when it is missing or fails. */
    @FunctionalInterface
    public interface ProcessRunner {
        Optional<String> run(List<String> command, Duration timeout);
    }

    /**
     * Runs a program in a directory and hands every line it prints to {@code output}; returns its
     * exit code. The program sees the environment of this process.
     */
    @FunctionalInterface
    public interface Launcher {
        int launch(List<String> command, Path directory, java.util.function.Consumer<String> output)
                throws IOException, InterruptedException;
    }

    /** An environment with the same values and another launcher. */
    public Environment withLauncher(Launcher value) {
        return new Environment(out, err, variables, properties, workingDirectory, userHome, interactive, runner, value);
    }

    public Environment {
        variables = Map.copyOf(variables);
        properties = Map.copyOf(properties);
    }

    /** The environment of this process. */
    public static Environment system() {
        Map<String, String> properties = new java.util.HashMap<>();
        System.getProperties().forEach((k, v) -> properties.put(String.valueOf(k), String.valueOf(v)));
        return new Environment(
                System.out,
                System.err,
                System.getenv(),
                properties,
                Path.of("").toAbsolutePath(),
                Path.of(System.getProperty("user.home")),
                terminal(),
                Environment::runProcess,
                Environment::launchProcess);
    }

    public @Nullable String variable(String name) {
        String value = variables.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    public @Nullable String property(String name) {
        String value = properties.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    /** The installation directory, set by the launcher, or by PIPEFORGE_HOME. */
    public @Nullable Path home() {
        String home = property("pipeforge.home");
        if (home == null) {
            home = variable("PIPEFORGE_HOME");
        }
        return home == null ? null : workingDirectory.resolve(home).normalize();
    }

    /** Per-user state: caches and logs. */
    public Path stateDirectory() {
        return userHome.resolve(".pipeforge");
    }

    /**
     * Whether the standard streams are a terminal. From JDK 22 {@code System.console()} is never
     * null, so {@code Console.isTerminal()} decides there.
     */
    @SuppressWarnings("SystemConsoleNull") // isTerminal() is used where it exists
    static boolean terminal() {
        java.io.Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            return (Boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    static int launchProcess(List<String> command, Path directory, java.util.function.Consumer<String> output)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.accept(line);
            }
        } finally {
            if (process.isAlive() && Thread.currentThread().isInterrupted()) {
                process.destroy();
            }
        }
        return process.waitFor();
    }

    static Optional<String> runProcess(List<String> command, Duration timeout) {
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
