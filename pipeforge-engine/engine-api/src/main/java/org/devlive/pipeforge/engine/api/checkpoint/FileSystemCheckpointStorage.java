/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api.checkpoint;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Checkpoints in a directory: the local disk of a single process, or a directory every master and
 * worker mounts at the same path (NFS). A file is written next to its place and renamed into it,
 * so readers never see half of it; {@code _metadata} is renamed into place last.
 */
public final class FileSystemCheckpointStorage implements CheckpointStorage {

    private final Path root;

    public FileSystemCheckpointStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public URI location() {
        return root.toUri();
    }

    private Path resolve(String path) {
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("'" + path + "' is outside the checkpoint storage " + root);
        }
        return resolved;
    }

    @Override
    public void write(String path, byte[] data) throws IOException {
        Path target = resolve(path);
        Path directory = target.getParent();
        if (directory == null || target.equals(root)) {
            throw new IllegalArgumentException("'" + path + "' is not a file of the checkpoint storage " + root);
        }
        Files.createDirectories(directory);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, data);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public byte[] read(String path) throws IOException {
        return Files.readAllBytes(resolve(path));
    }

    @Override
    public boolean exists(String path) {
        return Files.isRegularFile(resolve(path));
    }

    @Override
    public List<Entry> list(String prefix) throws IOException {
        Path start = resolve(prefix);
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.walk(start)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                    entries.add(new Entry(
                            root.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                            attributes.size(),
                            attributes.lastModifiedTime().toInstant()));
                } catch (NoSuchFileException e) {
                    // deleted while listing
                }
            }
        }
        return entries;
    }

    @Override
    public void delete(String path) throws IOException {
        Path target = resolve(path);
        if (target.equals(root)) {
            throw new IllegalArgumentException("The root of the checkpoint storage is not deleted");
        }
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> files = Files.walk(target)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Override
    public void complete(String directory, byte[] metadata) throws IOException {
        // write() renames the file into place: _metadata appears whole or not at all
        write(directory + CheckpointStorages.METADATA, metadata);
    }

    @Override
    public boolean isComplete(String directory) {
        return exists(directory + CheckpointStorages.METADATA);
    }
}
