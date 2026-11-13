/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every job in examples/ of the repository validates, explains and runs with the plugins of the
 * distribution, and its row counters reconcile.
 */
class ExamplesTest {

    static final Path EXAMPLES = Path.of("..", "examples").toAbsolutePath().normalize();

    @TempDir
    Path dir;

    Shell shell;

    @BeforeEach
    void setUp() throws Exception {
        shell = new Shell(dir.resolve("work"));
    }

    static List<Path> examples() throws Exception {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(EXAMPLES, "*.{yaml,yml}")) {
            stream.forEach(files::add);
        }
        files.sort(null);
        return files;
    }

    @Test
    void theFakeToConsoleExampleExists() throws Exception {
        assertThat(examples()).contains(EXAMPLES.resolve("fake-to-console.yaml"));
    }

    @Test
    void everyExampleValidatesExplainsAndRuns() throws Exception {
        for (Path example : examples()) {
            String file = example.toString();

            Shell.Result validate = shell.run("validate", file);
            assertThat(validate.exit())
                    .as("validate %s: %s", file, validate.out())
                    .isZero();

            Shell.Result explain = shell.run("explain", file);
            assertThat(explain.exit()).as("explain %s: %s", file, explain.err()).isZero();

            Shell.Result run = shell.run("run", "--local", file, "-o", "json");
            assertThat(run.exit())
                    .as("run %s: %s %s", file, run.out(), run.err())
                    .isZero();
            assertThat(run.out())
                    .as(file)
                    .contains("\"status\" : \"SUCCESS\"")
                    .contains("\"reconciled\" : true")
                    .contains("\"error\" : null");
        }
    }

    @Test
    void fakeToConsoleMapsFiveUsers() {
        Path example = EXAMPLES.resolve("fake-to-console.yaml");
        Shell.Result explain = shell.run("explain", example.toString());
        assertThat(explain.out())
                .contains("fake-to-console")
                .contains("user_id")
                .contains("initial");

        Shell.Result run = shell.run("run", "--local", example.toString());
        assertThat(run.exit()).isZero();
        assertThat(run.out()).startsWith("Job fake-to-console SUCCESS in ").contains("read 5 rows, wrote 5 rows");
    }
}
