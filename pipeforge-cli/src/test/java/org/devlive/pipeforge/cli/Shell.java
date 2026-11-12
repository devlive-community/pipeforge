/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runs the command in this JVM with a controlled environment and captures what it prints. */
final class Shell {

    /** The bundled plugins, copied by the build for the tests. */
    static final Path PLUGINS = Path.of(System.getProperty("pipeforge.plugins", "target/test-plugins"));

    record Result(int exit, String out, String err) {}

    final Path directory;
    final Map<String, String> variables = new HashMap<>();
    final Map<String, String> properties = new HashMap<>();
    final List<List<String>> commands = new ArrayList<>();
    Optional<String> docker = Optional.empty();

    Shell(Path directory) throws IOException {
        this.directory = Files.createDirectories(directory);
        variables.put("PIPEFORGE_LANG", "en-US");
        variables.put("PIPEFORGE_PLUGIN_PATH", PLUGINS.toAbsolutePath().toString());
        properties.put("java.vendor", "Test");
        properties.put("stdout.encoding", "UTF-8");
    }

    Path file(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    Environment environment(PrintStream out, PrintStream err) {
        return new Environment(
                out, err, variables, properties, directory, directory.resolve("home"), false, (command, timeout) -> {
                    commands.add(command);
                    return command.get(0).equals("docker") ? docker : Optional.empty();
                });
    }

    Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new Cli(environment(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8)))
                .run(args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static final String VERSION = VersionCommandVersion.VALUE;

    /** Text without the parts that change between builds and machines. */
    String normalize(String text) {
        return text.replace(PLUGINS.toAbsolutePath().toString(), "<plugins>")
                .replace(directory.toString(), "<dir>")
                .replace(VERSION, "<version>")
                .replace(org.devlive.pipeforge.api.ApiVersion.current(), "<api>");
    }

    /**
     * Compares a text with src/test/resources/golden/{@code name}; -Dpipeforge.golden.update=true
     * writes it instead.
     */
    void assertGolden(String name, String actual) throws IOException {
        Path golden = Path.of("src/test/resources/golden").resolve(name);
        String text = normalize(actual);
        if (Boolean.getBoolean("pipeforge.golden.update")) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, text, StandardCharsets.UTF_8);
        }
        try (InputStream in = Shell.class.getResourceAsStream("/golden/" + name)) {
            assertThat(in).as("golden file %s", name).isNotNull();
            assertThat(text)
                    .as("output compared with golden/%s", name)
                    .isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static Duration seconds(long value) {
        return Duration.ofSeconds(value);
    }

    /** The version of this build. */
    static final class VersionCommandVersion {
        static final String VALUE = org.devlive.pipeforge.cli.command.VersionCommand.version();

        private VersionCommandVersion() {}
    }
}
