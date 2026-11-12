/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** pipeforge plugin validate, test and docs; Maven is stubbed here and runs for real in the integration test. */
class PluginCommandsTest {

    @TempDir
    Path dir;

    Shell shell;

    @BeforeEach
    void setUp() throws Exception {
        shell = new Shell(dir.resolve("work"));
    }

    static Path bundled(String prefix) throws Exception {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Shell.PLUGINS, prefix + "-*.zip")) {
            return files.iterator().next().toAbsolutePath();
        }
    }

    /** A copy of a package without its documentation data and license. */
    Path broken(Path source) throws Exception {
        Path target = dir.resolve("broken.zip");
        try (ZipFile zip = new ZipFile(source.toFile());
                OutputStream out = Files.newOutputStream(target);
                ZipOutputStream copy = new ZipOutputStream(out)) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (entry.getName().startsWith("docs/")) {
                    continue;
                }
                copy.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    if (entry.getName().equals("plugin.yaml")) {
                        bytes = new String(bytes, StandardCharsets.UTF_8)
                                .replaceAll("(?m)^license: .*\\n", "")
                                .getBytes(StandardCharsets.UTF_8);
                    }
                    copy.write(bytes);
                }
                copy.closeEntry();
            }
        }
        return target;
    }

    @Test
    void validatesTheBundledPackages() throws Exception {
        Shell.Result result = shell.run(
                "plugin",
                "validate",
                bundled("connector-console").toString(),
                bundled("connector-fake").toString());
        assertThat(result.exit()).isZero();
        String text = shell.normalize(result.out());
        assertThat(text)
                .contains("console@<version>  <plugins>/connector-console-<version>.zip\n  OK  descriptor")
                .contains("  OK  docs")
                .contains("  sink console, capabilities: -")
                .contains("  source fake, capabilities: SupportsMultiTable, SupportsRowCount")
                .endsWith("2 package(s) passed, 0 failed\n");
    }

    @Test
    void reportsEveryProblemOfABrokenPackage() throws Exception {
        Path broken = broken(bundled("connector-blackhole"));
        Shell.Result result = shell.run("plugin", "validate", broken.toString());
        assertThat(result.exit()).isEqualTo(ExitCode.INVALID.code());
        assertThat(result.out())
                .contains("  FAIL  descriptor       plugin.yaml declares no license")
                .contains("  OK    isolation")
                .contains("  FAIL  docs             docs/blackhole.json is missing")
                .endsWith("0 package(s) passed, 1 failed\n");

        Shell.Result json = shell.run("-o", "json", "plugin", "validate", broken.toString());
        assertThat(json.out())
                .contains("\"plugin\" : \"blackhole@")
                .contains("\"passed\" : false")
                .contains("\"name\" : \"forbidden-calls\"");
        assertThat(shell.run("plugin", "validate", "nothing.zip").exit()).isEqualTo(6);
    }

    /** A plugin project as Maven would leave it after running the tests. */
    Path project() throws Exception {
        shell.file("greeting/pom.xml", "<project/>");
        shell.file(
                "greeting/src/main/plugin/plugin.yaml",
                "id: greeting\nname: Greeting\nversion: 1.0.0\napi-version: \"0.x\"\nprovides:\n"
                        + "  - { type: source, identifier: greeting, class: org.example.Greeting }\n");
        shell.file(
                "greeting/target/surefire-reports/TEST-a.xml",
                "<?xml version=\"1.0\"?><testsuite name=\"a\" tests=\"12\" failures=\"1\" errors=\"0\" skipped=\"3\"/>");
        shell.file(
                "greeting/target/surefire-reports/TEST-b.xml",
                "<?xml version=\"1.0\"?><testsuite name=\"b\" tests=\"4\" failures=\"0\" errors=\"2\" skipped=\"\"/>");
        return dir.resolve("work/greeting");
    }

    @Test
    void runsTheTestsWithMaven() throws Exception {
        Path project = project();
        shell.launch = "[INFO] BUILD SUCCESS";
        Shell.Result result =
                shell.run("plugin", "test", "greeting", "--groups", "options,types", "--test", "*ContractTest");
        assertThat(result.exit()).isZero();
        assertThat(shell.commands.get(0))
                .containsExactly(
                        "mvn",
                        "-B",
                        "-f",
                        project.resolve("pom.xml").toString(),
                        "test",
                        "-Dpipeforge.testkit.groups=options,types",
                        "-Dtest=*ContractTest",
                        "-Dsurefire.failIfNoSpecifiedTests=false");
        assertThat(shell.launched).containsExactly(project);
        assertThat(result.out()).isEqualTo("Tests passed: 16 run, 1 failed, 2 errors, 3 skipped\n");
        assertThat(result.err()).contains("Running mvn -B -f").contains("[INFO] BUILD SUCCESS");

        shell.launchExit = 1;
        shell.file("mvnw", "#!/bin/sh");
        Shell.Result failed = shell.run("-q", "-o", "json", "plugin", "test", "greeting");
        assertThat(failed.exit()).isEqualTo(ExitCode.RUN_FAILED.code());
        assertThat(shell.commands.get(1)).startsWith(dir.resolve("work/mvnw").toString(), "-B", "-q", "-f");
        assertThat(failed.out()).contains("\"passed\" : false").contains("\"tests\" : 16");
        assertThat(failed.err()).doesNotContain("Running");

        // Reports of an earlier build are not counted.
        Files.setLastModifiedTime(
                project.resolve("target/surefire-reports/TEST-a.xml"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        assertThat(shell.run("-o", "json", "plugin", "test", "greeting").out()).contains("\"tests\" : 4");
    }

    @Test
    void writesAndChecksTheDocumentationData() throws Exception {
        Path project = project();
        shell.file(
                "greeting/src/generated/plugin-docs/greeting.json",
                "{\"components\":[{\"type\":\"source\",\"identifier\":\"greeting\",\"options\":[{\"key\":\"name\"},{\"key\":\"count\"}]},"
                        + "{\"type\":\"sink\",\"identifier\":\"quiet\",\"options\":[]}]}");
        Shell.Result result = shell.run("plugin", "docs", "greeting", "--maven", "/opt/maven/bin/mvn");
        assertThat(result.exit()).isZero();
        assertThat(shell.commands.get(0))
                .containsExactly(
                        "/opt/maven/bin/mvn",
                        "-B",
                        "-f",
                        project.resolve("pom.xml").toString(),
                        "compile",
                        "pipeforge:docs",
                        "-Dpipeforge.docs.outputDirectory=" + project.resolve("src/generated/plugin-docs"));
        assertThat(result.out())
                .isEqualTo(
                        "Wrote greeting/src/generated/plugin-docs/greeting.json\nsource greeting  name, count\nsink quiet       -\n");

        Shell.Result current = shell.run(
                "plugin", "docs", "greeting", "--check", "--output-dir", "greeting/src/generated/plugin-docs");
        assertThat(current.out()).startsWith("greeting/src/generated/plugin-docs/greeting.json is current\n");
        assertThat(shell.commands.get(1)).contains("-Dpipeforge.docs.check=true");

        shell.launchExit = 1;
        Shell.Result stale = shell.run("plugin", "docs", "greeting", "--check");
        assertThat(stale.exit()).isEqualTo(ExitCode.INVALID.code());
        assertThat(stale.err()).contains("The documentation data is stale");
        assertThat(shell.run("plugin", "docs", "greeting").exit()).isEqualTo(ExitCode.RUN_FAILED.code());
    }

    @Test
    void needsAPluginProjectAndMaven() throws Exception {
        shell.file("plain/pom.xml", "<project/>");
        Shell.Result plain = shell.run("plugin", "test", "plain");
        assertThat(plain.exit()).isEqualTo(6);
        assertThat(plain.err()).contains("plain/src/main/plugin/plugin.yaml").contains("not a plugin project");
        assertThat(shell.run("plugin", "docs", "nowhere").err()).contains("nowhere/pom.xml");

        project();
        shell.launchFails = true;
        Shell.Result noMaven = shell.run("plugin", "test", "greeting");
        assertThat(noMaven.exit()).isEqualTo(6);
        assertThat(noMaven.err()).contains("Maven could not be started").contains("--maven");
        assertThat(shell.run("plugin").exit()).isEqualTo(2);
    }
}
