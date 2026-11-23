/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api.checkpoint;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/** Opens checkpoint storage by its location, checks it, and finds what it holds that nothing knows. */
public final class CheckpointStorages {

    /** The metadata file of a checkpoint. */
    public static final String METADATA = "_metadata";

    /** The marker object storage writes after {@code _metadata}. */
    public static final String COMPLETED = "_COMPLETED";

    private CheckpointStorages() {}

    /**
     * The storage at {@code location}: a path or {@code file:} URI for a directory (local or a shared
     * NFS mount), else the factory of its scheme, such as {@code s3://bucket/prefix}.
     *
     * @param settings the {@code checkpoint.*} settings without the prefix, for the factory
     * @throws PipeForgeException {@link ErrorCode#STORAGE_UNAVAILABLE} for a location no storage
     *     takes
     */
    public static CheckpointStorage open(String location, Map<String, String> settings) {
        URI uri;
        try {
            uri = new URI(location.strip());
        } catch (URISyntaxException e) {
            uri = null;
        }
        if (uri == null || uri.getScheme() == null || uri.getScheme().length() == 1) {
            // a plain path; one letter is a Windows drive, not a scheme
            return new FileSystemCheckpointStorage(Path.of(location.strip()));
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("file")) {
            return new FileSystemCheckpointStorage(Path.of(uri));
        }
        List<String> known = new ArrayList<>(List.of("file"));
        for (CheckpointStorageFactory factory :
                ServiceLoader.load(CheckpointStorageFactory.class, CheckpointStorages.class.getClassLoader())) {
            if (factory.scheme().equalsIgnoreCase(scheme)) {
                return factory.open(uri, settings);
            }
            known.add(factory.scheme());
        }
        throw unavailable(
                location,
                "no checkpoint storage takes '" + scheme + ":' locations; known: " + String.join(", ", known));
    }

    /**
     * Writes, reads and deletes a small file, so that a process finds out at its start that it
     * cannot keep checkpoints, not at the first checkpoint.
     *
     * @throws PipeForgeException {@link ErrorCode#STORAGE_UNAVAILABLE} with the cause
     */
    public static void probe(CheckpointStorage storage, String who) {
        String path = "_probes/" + who.replaceAll("[^A-Za-z0-9_.-]", "_") + "-" + UUID.randomUUID();
        byte[] content = ("probe of " + who).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            storage.write(path, content);
            if (!java.util.Arrays.equals(storage.read(path), content)) {
                throw new IOException("a file read back differs from the one written");
            }
            storage.delete(path);
        } catch (IOException | RuntimeException e) {
            throw PipeForgeException.of(ErrorCode.STORAGE_UNAVAILABLE)
                    .param("location", String.valueOf(storage.location()))
                    .param("reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .cause(e)
                    .build();
        }
    }

    /**
     * Checkpoint and savepoint directories that nothing knows — not in {@code known} — and that
     * nothing was written to for {@code age}: left by a checkpoint that failed half-way or a run
     * whose records are gone. Savepoints are only listed, never orphans: nobody deletes them
     * automatically.
     */
    public static List<String> orphans(CheckpointStorage storage, Set<String> known, Instant now, Duration age)
            throws IOException {
        Map<String, Instant> newest = new TreeMap<>();
        for (CheckpointStorage.Entry entry : storage.list("")) {
            String directory = CheckpointPaths.directoryOf(entry.path());
            if (directory == null || directory.contains("/savepoints/")) {
                continue;
            }
            newest.merge(directory, entry.modified(), (a, b) -> a.isAfter(b) ? a : b);
        }
        List<String> orphans = new ArrayList<>();
        Map<String, Instant> candidates = new LinkedHashMap<>(newest);
        candidates.forEach((directory, modified) -> {
            if (!known.contains(directory) && !modified.isAfter(now.minus(age))) {
                orphans.add(directory);
            }
        });
        return orphans;
    }

    static PipeForgeException unavailable(String location, String reason) {
        return PipeForgeException.of(ErrorCode.STORAGE_UNAVAILABLE)
                .param("location", location)
                .param("reason", reason)
                .build();
    }
}
