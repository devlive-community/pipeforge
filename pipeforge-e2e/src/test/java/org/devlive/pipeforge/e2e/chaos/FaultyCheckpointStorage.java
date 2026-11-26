/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage;

/** Checkpoints in a directory, with the faults its control file asks for applied to every write. */
final class FaultyCheckpointStorage implements CheckpointStorage {

    private final URI location;
    private final FileSystemCheckpointStorage files;
    private final Path control;

    FaultyCheckpointStorage(URI location, Path directory, Path control) {
        this.location = location;
        this.files = new FileSystemCheckpointStorage(directory);
        this.control = control;
    }

    /** Applies what the control file says; returns the bytes to write. */
    private byte[] before(String what, byte[] data) throws IOException {
        StorageControl now = StorageControl.read(control);
        switch (now.mode()) {
            case FAIL -> throw new IOException("chaos: the checkpoint storage refuses to write " + what);
            case SLOW -> {
                try {
                    Thread.sleep(now.slowMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while the checkpoint storage was slow", e);
                }
            }
            case TRUNCATE -> {
                return Arrays.copyOf(data, data.length / 2);
            }
            case OK -> {
                // as it is
            }
        }
        return data;
    }

    @Override
    public URI location() {
        return location;
    }

    @Override
    public void write(String path, byte[] data) throws IOException {
        files.write(path, before(path, data));
    }

    @Override
    public byte[] read(String path) throws IOException {
        return files.read(path);
    }

    @Override
    public boolean exists(String path) throws IOException {
        return files.exists(path);
    }

    @Override
    public List<Entry> list(String prefix) throws IOException {
        return files.list(prefix);
    }

    @Override
    public void delete(String path) throws IOException {
        files.delete(path);
    }

    @Override
    public void complete(String directory, byte[] metadata) throws IOException {
        files.complete(directory, before(directory + "_metadata", metadata));
    }

    @Override
    public boolean isComplete(String directory) throws IOException {
        return files.isComplete(directory);
    }

    @Override
    public void close() throws IOException {
        files.close();
    }
}
