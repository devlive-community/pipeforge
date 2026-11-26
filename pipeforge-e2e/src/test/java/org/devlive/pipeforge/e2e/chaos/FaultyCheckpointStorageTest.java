/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The checkpoint storage whose writes fail, stall and keep half a file on command. */
class FaultyCheckpointStorageTest {

    @TempDir
    Path dir;

    @Test
    void itsJarIsFoundOnTheClassPathOfAProcess() throws Exception {
        Path jar = ChaosJar.write(dir.resolve("lib/pipeforge-chaos.jar"));
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThat(ServiceLoader.load(CheckpointStorageFactory.class, loader).stream()
                            .map(p -> p.get().scheme()))
                    .contains(FaultyCheckpointStorageFactory.SCHEME);
        }
        // the synthetic classes go too, such as the one of a switch over an enum
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            assertThat(file.stream().map(java.util.jar.JarEntry::getName))
                    .contains("org/devlive/pipeforge/e2e/chaos/FaultyCheckpointStorage$1.class")
                    .contains("org/devlive/pipeforge/e2e/chaos/StorageControl$Mode.class");
        }
    }

    @Test
    void writesFailStallOrKeepHalfAFileAsTheControlFileSays() throws Exception {
        Path control = dir.resolve("control");
        String location = FaultyCheckpointStorageFactory.location(dir.resolve("checkpoints"), control);
        try (CheckpointStorage storage = new FaultyCheckpointStorageFactory().open(URI.create(location), Map.of())) {
            assertThat(storage.location()).isEqualTo(URI.create(location));
            byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
            storage.write("run/chk-1/a.state", data);
            assertThat(storage.read("run/chk-1/a.state")).isEqualTo(data);

            new StorageControl(StorageControl.Mode.FAIL, 0).write(control);
            assertThatThrownBy(() -> storage.write("run/chk-2/a.state", data))
                    .hasMessageContaining("chaos: the checkpoint storage refuses to write run/chk-2/a.state");
            assertThatThrownBy(() -> storage.complete("run/chk-2/", data)).isInstanceOf(java.io.IOException.class);

            new StorageControl(StorageControl.Mode.SLOW, 300).write(control);
            long started = System.nanoTime();
            storage.write("run/chk-3/a.state", data);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isGreaterThanOrEqualTo(300);

            new StorageControl(StorageControl.Mode.TRUNCATE, 0).write(control);
            storage.write("run/chk-4/a.state", data);
            assertThat(storage.read("run/chk-4/a.state")).hasSize(5);

            StorageControl.OK.write(control);
            storage.complete("run/chk-1/", data);
            assertThat(storage.isComplete("run/chk-1/")).isTrue();
            assertThat(storage.exists("run/chk-1/a.state")).isTrue();
            assertThat(storage.list("run/")).isNotEmpty();
            storage.delete("run/chk-4/a.state");
            assertThat(storage.exists("run/chk-4/a.state")).isFalse();
        }
        assertThat(StorageControl.read(dir.resolve("missing"))).isEqualTo(StorageControl.OK);
        Files.writeString(control, "slow");
        assertThat(StorageControl.read(control).slowMs()).isEqualTo(1000);
        assertThatThrownBy(() -> new FaultyCheckpointStorageFactory().open(URI.create("faulty:///tmp/x"), Map.of()))
                .hasMessageContaining("names no control file");
    }
}
