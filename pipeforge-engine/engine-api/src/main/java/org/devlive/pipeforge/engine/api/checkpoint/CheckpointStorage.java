/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api.checkpoint;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Where checkpoints are kept: files under one root, addressed by relative paths with {@code /}.
 * Paths follow {@link CheckpointPaths}. Implementations: the file system (local disk or a shared
 * NFS directory) and, from {@code pipeforge-engine-checkpoint-s3}, S3 and S3-compatible object
 * storage; {@link CheckpointStorages#open} chooses one by the scheme of the location.
 */
public interface CheckpointStorage extends AutoCloseable {

    /** A file of the storage. */
    record Entry(String path, long size, Instant modified) {}

    /** The root, for messages. */
    URI location();

    /** Writes a whole file, replacing it; readers never see half of it. */
    void write(String path, byte[] data) throws IOException;

    /**
     * A whole file.
     *
     * @throws java.nio.file.NoSuchFileException when it does not exist
     */
    byte[] read(String path) throws IOException;

    boolean exists(String path) throws IOException;

    /** Every file under {@code prefix} (a directory, or {@code ""} for all), with paths from the root. */
    List<Entry> list(String prefix) throws IOException;

    /** Deletes a file, or a directory with everything under it; nothing to delete is fine. */
    void delete(String path) throws IOException;

    /**
     * Writes the {@code _metadata} of the checkpoint in {@code directory} so that the checkpoint
     * counts as complete only once it is whole: renamed into place on a file system, followed by a
     * {@code _COMPLETED} marker on object storage (protocol specification, section 5.3).
     */
    void complete(String directory, byte[] metadata) throws IOException;

    /** Whether the checkpoint in {@code directory} was completed. */
    boolean isComplete(String directory) throws IOException;

    @Override
    default void close() throws IOException {}
}
