/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Checkpoint storage on a file system, its layout, opening it by location, probing it and orphans. */
class CheckpointStorageTest {

    @TempDir
    Path root;

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writesReadsListsAndDeletesFiles() throws Exception {
        try (CheckpointStorage storage = new FileSystemCheckpointStorage(root)) {
            String chk = CheckpointPaths.checkpoint("job-1", 7);
            storage.write(chk + "orders-0.state", bytes("a"));
            storage.write(chk + "orders-1.state", bytes("bb"));
            storage.write(chk + "orders-1.state", bytes("ccc"));
            assertThat(storage.read(chk + "orders-1.state")).isEqualTo(bytes("ccc"));
            assertThat(storage.exists(chk + "orders-0.state")).isTrue();
            assertThat(storage.exists(chk + "nothing")).isFalse();
            assertThatThrownBy(() -> storage.read(chk + "nothing")).isInstanceOf(NoSuchFileException.class);
            assertThat(storage.list("job-1/"))
                    .extracting(CheckpointStorage.Entry::path, CheckpointStorage.Entry::size)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("job-1/chk-7/orders-0.state", 1L),
                            org.assertj.core.groups.Tuple.tuple("job-1/chk-7/orders-1.state", 3L));
            assertThat(storage.list("other/")).isEmpty();
            try (var files = Files.walk(root)) {
                assertThat(files.map(Path::toString)).noneMatch(p -> p.endsWith(".tmp"));
            }
            storage.delete(chk);
            assertThat(storage.list("")).isEmpty();
            storage.delete(chk);
            assertThat(storage.location()).isEqualTo(root.toUri());
        }
    }

    @Test
    void aCheckpointIsCompleteOnlyWithItsWholeMetadata() throws Exception {
        CheckpointStorage storage = new FileSystemCheckpointStorage(root);
        String chk = CheckpointPaths.checkpoint("job-1", 8);
        storage.write(chk + "orders-0.state", bytes("a"));
        // a crash before the rename leaves only a temporary file
        Files.writeString(root.resolve("job-1/chk-8/_metadata.9f.tmp"), "half");
        assertThat(storage.isComplete(chk)).isFalse();
        storage.complete(chk, bytes("metadata"));
        assertThat(storage.isComplete(chk)).isTrue();
        assertThat(storage.read(chk + CheckpointStorages.METADATA)).isEqualTo(bytes("metadata"));
    }

    @Test
    void pathsStayInsideTheStorage() {
        CheckpointStorage storage = new FileSystemCheckpointStorage(root);
        assertThatThrownBy(() -> storage.write("../escape", bytes("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("")).hasMessage("The root of the checkpoint storage is not deleted");
        assertThatThrownBy(() -> CheckpointPaths.checkpoint("../job", 1))
                .hasMessage("'../job' is not a name for checkpoint storage: use letters, digits, '_', '-' and '.'");
        assertThatThrownBy(() -> CheckpointPaths.savepoint("job", "a/b", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLayoutOfAJob() {
        assertThat(CheckpointPaths.checkpoint("job-1", 12)).isEqualTo("job-1/chk-12/");
        assertThat(CheckpointPaths.savepoint("job-1", "before-upgrade", 1_763_798_400_000L))
                .isEqualTo("job-1/savepoints/before-upgrade-1763798400000/");
        assertThat(CheckpointPaths.file("job-1/chk-12/", "enum-orders.state"))
                .isEqualTo("job-1/chk-12/enum-orders.state");
        assertThat(CheckpointPaths.directoryOf("job-1/chk-12/orders-0.state")).isEqualTo("job-1/chk-12/");
        assertThat(CheckpointPaths.directoryOf("job-1/savepoints/s-1/_metadata"))
                .isEqualTo("job-1/savepoints/s-1/");
        assertThat(CheckpointPaths.directoryOf("_probes/x")).isNull();
    }

    @Test
    void opensStorageByItsLocation() throws Exception {
        assertThat(CheckpointStorages.open(root.toString(), Map.of())).isInstanceOf(FileSystemCheckpointStorage.class);
        try (CheckpointStorage storage = CheckpointStorages.open(root.toUri().toString(), Map.of())) {
            assertThat(storage.location()).isEqualTo(root.toUri());
        }
        assertThatThrownBy(() -> CheckpointStorages.open("hdfs://namenode/checkpoints", Map.of()))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
                    assertThat(e.getMessage())
                            .isEqualTo("PF-CKPT-0004 The checkpoint storage hdfs://namenode/checkpoints cannot be used:"
                                    + " no checkpoint storage takes 'hdfs:' locations; known: file");
                });
    }

    @Test
    void aProbeLeavesNothingBehind() throws Exception {
        CheckpointStorage storage = new FileSystemCheckpointStorage(root);
        CheckpointStorages.probe(storage, "worker-1");
        assertThat(storage.list("")).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aStorageThatCannotBeWrittenFailsTheProbe() throws Exception {
        Path locked = Files.createDirectory(root.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThatThrownBy(() -> CheckpointStorages.probe(new FileSystemCheckpointStorage(locked), "master"))
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
                        assertThat(e.getMessage()).startsWith("PF-CKPT-0004 The checkpoint storage " + locked.toUri());
                    });
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void orphansAreOldCheckpointsNothingKnowsButNeverSavepoints() throws Exception {
        CheckpointStorage storage = new FileSystemCheckpointStorage(root);
        storage.write("job-1/chk-1/_metadata", bytes("m"));
        storage.write("job-1/chk-2/orders-0.state", bytes("s"));
        storage.write("job-1/chk-3/orders-0.state", bytes("s"));
        storage.write("job-1/savepoints/old-1/_metadata", bytes("m"));
        Instant now = Instant.now();
        FileTime day = FileTime.from(now.minus(Duration.ofDays(2)));
        for (String file :
                new String[] {"job-1/chk-1/_metadata", "job-1/chk-2/orders-0.state", "job-1/savepoints/old-1/_metadata"
                }) {
            Files.setLastModifiedTime(root.resolve(file), day);
        }
        // chk-1 is known, chk-2 is old and unknown, chk-3 is new
        assertThat(CheckpointStorages.orphans(storage, Set.of("job-1/chk-1/"), now, Duration.ofHours(24)))
                .containsExactly("job-1/chk-2/");
    }
}
