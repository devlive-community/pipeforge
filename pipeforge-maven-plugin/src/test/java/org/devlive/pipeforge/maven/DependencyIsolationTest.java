/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.devlive.pipeforge.maven.DependencyIsolation.Dependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyIsolationTest {

    private static Dependency dep(String group, String artifact, String scope, File file) {
        return new Dependency(group, artifact, "1.0", scope, file);
    }

    static File jar(Path dir, String name, String... entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(new byte[] {1});
                zip.closeEntry();
            }
        }
        return file.toFile();
    }

    @Test
    void requiresProvidedApiAndRejectsEngineModules() {
        File none = new File("none.jar");
        List<String> problems = DependencyIsolation.checkScopes(List.of(
                dep("org.devlive.pipeforge", "pipeforge-api", "provided", none),
                dep("org.devlive.pipeforge", "pipeforge-common", "compile", none),
                dep("org.devlive.pipeforge", "engine-worker", "provided", none),
                dep("org.devlive.pipeforge", "pipeforge-connector-testkit", "test", none),
                dep("com.example", "client", "compile", none)));
        assertThat(problems)
                .containsExactly(
                        "org.devlive.pipeforge:pipeforge-common:1.0 must have provided scope; the engine supplies it",
                        "plugins must not depend on org.devlive.pipeforge:engine-worker:1.0; only pipeforge-api (provided) is allowed");
    }

    @Test
    void shipsOnlyCompileAndRuntimeDependencies() {
        File none = new File("none.jar");
        assertThat(DependencyIsolation.shipped(List.of(
                                dep("a", "compile", "compile", none),
                                dep("a", "runtime", "runtime", none),
                                dep("a", "provided", "provided", none),
                                dep("a", "test", "test", none),
                                dep("a", "system", "system", none)))
                        .stream()
                        .map(Dependency::artifactId))
                .containsExactly("compile", "runtime");
    }

    @Test
    void findsDuplicateAndPipeForgeClasses(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes/com/example"));
        Files.write(classes.resolve("Plugin.class"), new byte[] {1});
        File a = jar(
                dir,
                "a.jar",
                "com/example/Plugin.class",
                "com/lib/Util.class",
                "META-INF/versions/9/x.class",
                "module-info.class");
        File b = jar(dir, "b.jar", "com/lib/Util.class", "org/devlive/pipeforge/api/row/Row.class", "README.txt");
        List<String> problems = DependencyIsolation.checkClasses(
                dir.resolve("classes"),
                List.of(
                        dep("x", "a", "compile", a),
                        dep("x", "b", "runtime", b),
                        dep("x", "gone", "compile", new File("missing.jar"))));
        assertThat(problems)
                .containsExactly(
                        "x:b:1.0 contains PipeForge classes (org/devlive/pipeforge/api/row/Row.class)",
                        "2 duplicate class(es), e.g. com.example.Plugin in [plugin classes, x:a:1.0]; com.lib.Util in [x:a:1.0, x:b:1.0]");
    }

    @Test
    void acceptsCleanClasses(@TempDir Path dir) throws IOException {
        File a = jar(dir, "a.jar", "com/lib/A.class");
        assertThat(DependencyIsolation.checkClasses(dir.resolve("absent"), List.of(dep("x", "a", "compile", a))))
                .isEmpty();
    }
}
