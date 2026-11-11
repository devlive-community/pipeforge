/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevClasspathTest {

    @TempDir
    Path dir;

    @Test
    void writesOneAbsolutePathPerLineAfterAHeader() throws Exception {
        Path file = dir.resolve("target").resolve(DevClasspath.FILE);
        Path jar = dir.resolve("repo/../repo/lib-1.0.jar");

        assertThat(DevClasspath.write(file, List.of(jar, dir.resolve("b.jar")))).isTrue();

        assertThat(Files.readAllLines(file))
                .containsExactly(
                        DevClasspath.HEADER,
                        dir.resolve("repo/lib-1.0.jar").toAbsolutePath().toString(),
                        dir.resolve("b.jar").toAbsolutePath().toString());
    }

    @Test
    void leavesAnUnchangedListAlone() throws Exception {
        Path file = dir.resolve(DevClasspath.FILE);
        DevClasspath.write(file, List.of(dir.resolve("a.jar")));
        FileTime written = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(file, written);

        assertThat(DevClasspath.write(file, List.of(dir.resolve("a.jar")))).isFalse();
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(written);

        assertThat(DevClasspath.write(file, List.of())).isTrue();
        assertThat(Files.readAllLines(file)).containsExactly(DevClasspath.HEADER);
    }
}
