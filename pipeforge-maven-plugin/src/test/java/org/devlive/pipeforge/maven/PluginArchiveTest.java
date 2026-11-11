/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArchiveTest {

    @Test
    void writesASortedReproducibleArchive(@TempDir Path dir) throws IOException {
        Path descriptor = Files.writeString(dir.resolve("plugin.yaml"), "id: x\n");
        Path jar = DependencyIsolationTest.jar(dir, "x-1.0.jar", "X.class").toPath();
        Path lib = DependencyIsolationTest.jar(dir, "lib-2.0.jar", "L.class").toPath();
        Path docs = Files.writeString(dir.resolve("x.json"), "{}");
        Path natives = Files.createDirectories(dir.resolve("src/native-options/v1"));
        Files.writeString(natives.resolve("client.yaml"), "a: 1");
        Path icon = Files.writeString(dir.resolve("src/icon.svg"), "<svg/>");

        Path first = dir.resolve("out/x-1.0.zip");
        PluginArchive.write(
                first,
                descriptor,
                List.of(jar, lib),
                List.of(docs),
                List.of(dir.resolve("src/native-options"), dir.resolve("absent")),
                icon);
        try (ZipFile zip = new ZipFile(first.toFile())) {
            assertThat(Collections.list(zip.entries()))
                    .extracting(e -> e.getName())
                    .containsExactly(
                            "docs/x.json",
                            "icon.svg",
                            "lib/lib-2.0.jar",
                            "lib/x-1.0.jar",
                            "native-options/v1/client.yaml",
                            "plugin.yaml");
        }
        Path second = dir.resolve("out/again.zip");
        PluginArchive.write(
                second, descriptor, List.of(lib, jar), List.of(docs), List.of(dir.resolve("src/native-options")), icon);
        assertThat(Files.readAllBytes(second)).isEqualTo(Files.readAllBytes(first));
    }

    @Test
    void rejectsTwoFilesWithTheSameName(@TempDir Path dir) throws IOException {
        Path descriptor = Files.writeString(dir.resolve("plugin.yaml"), "id: x\n");
        Path a = DependencyIsolationTest.jar(Files.createDirectories(dir.resolve("a")), "same.jar")
                .toPath();
        Path b = DependencyIsolationTest.jar(Files.createDirectories(dir.resolve("b")), "same.jar")
                .toPath();
        assertThatThrownBy(() -> PluginArchive.write(
                        dir.resolve("x.zip"), descriptor, List.of(a, b), List.of(), List.of(), dir.resolve("none.svg")))
                .hasMessageContaining("Two files would be packaged as lib/same.jar");
    }
}
