/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage;

/** A directory whose writes wait until released, as slow object storage would; can fail them. */
final class BlockingStorage implements CheckpointStorage {

    final FileSystemCheckpointStorage files;
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch writing = new CountDownLatch(1);
    volatile boolean fail;

    BlockingStorage(Path dir) {
        this.files = new FileSystemCheckpointStorage(dir);
    }

    @Override
    public URI location() {
        return files.location();
    }

    @Override
    public void write(String path, byte[] data) throws IOException {
        writing.countDown();
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IOException("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (fail) {
            throw new IOException("the bucket is gone");
        }
        files.write(path, data);
    }

    @Override
    public byte[] read(String path) throws IOException {
        return files.read(path);
    }

    @Override
    public boolean exists(String path) {
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
        files.complete(directory, metadata);
    }

    @Override
    public boolean isComplete(String directory) {
        return files.isComplete(directory);
    }
}
