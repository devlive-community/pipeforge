/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Measures how long {@code pipeforge version} takes from the start of the JVM to its end, with the
 * class data sharing archive that bin/pipeforge uses. The target is 300 ms; the test records the
 * median in target/startup.txt and fails only far above it, so a busy machine does not fail the
 * build.
 */
class StartupTest {

    @Test
    void startsWellWithinASecond() throws Exception {
        Path archive = Path.of("target", "startup-test.jsa");
        Files.deleteIfExists(archive);
        // Class data sharing needs jars: the classes of this module are packed into one first.
        Path jar = Path.of("target", "startup", "pipeforge-cli.jar");
        pack(Path.of("target", "classes"), jar);
        List<String> classpath = new ArrayList<>(List.of(jar.toString()));
        for (String entry : java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(java.io.File.pathSeparator))
                .splitAsStream(System.getProperty("java.class.path"))
                .toList()) {
            if (entry.endsWith(".jar") && !entry.contains("junit") && !entry.contains("assertj")) {
                classpath.add(entry);
            }
        }
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+AutoCreateSharedArchive",
                "-XX:SharedArchiveFile=" + archive,
                "-Xlog:cds=off",
                "-Xlog:cds+dynamic=off",
                "-cp",
                String.join(java.io.File.pathSeparator, classpath),
                Main.class.getName(),
                "version");
        long first = run(command);
        List<Long> warm = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            warm.add(run(command));
        }
        warm.sort(Long::compare);
        long median = warm.get(warm.size() / 2);
        Files.writeString(
                Path.of("target", "startup.txt"),
                "pipeforge version: first run " + first + " ms (writes the archive), warm median " + median
                        + " ms, min " + warm.get(0) + " ms, max " + warm.get(warm.size() - 1) + " ms\n",
                StandardCharsets.UTF_8);
        assertThat(archive).exists();
        assertThat(median).as("warm start in ms").isLessThan(1000);
    }

    private static void pack(Path classes, Path jar) throws Exception {
        Files.createDirectories(jar.getParent());
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar));
                java.util.stream.Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new java.util.jar.JarEntry(
                        classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
    }

    private static long run(List<String> command) throws Exception {
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).startsWith("pipeforge ");
        return (System.nanoTime() - started) / 1_000_000;
    }
}
