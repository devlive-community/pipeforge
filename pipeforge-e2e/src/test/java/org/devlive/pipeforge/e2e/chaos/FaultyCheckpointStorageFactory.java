/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorageFactory;

/**
 * Checkpoint storage whose writes fail, stall or keep half a file on command: {@code
 * faulty:///<directory>?control=<file>} keeps checkpoints in the directory as {@code file:} does,
 * and before every write reads what the control file says (see {@link StorageControl}). The chaos
 * framework puts this class into the copies of the distribution its processes run from, so the
 * distribution itself stays as it ships.
 */
public final class FaultyCheckpointStorageFactory implements CheckpointStorageFactory {

    public static final String SCHEME = "faulty";

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public CheckpointStorage open(URI location, Map<String, String> settings) {
        String query = location.getQuery() == null ? "" : location.getQuery();
        String control = null;
        for (String part : query.split("&")) {
            if (part.startsWith("control=")) {
                control = part.substring("control=".length());
            }
        }
        if (control == null || control.isBlank()) {
            throw new IllegalArgumentException(location + " names no control file: ?control=<file>");
        }
        return new FaultyCheckpointStorage(location, Path.of(location.getPath()), Path.of(control));
    }

    /** The location of checkpoints in {@code directory} that {@code control} steers. */
    public static String location(Path directory, Path control) {
        return SCHEME + "://" + directory.toAbsolutePath() + "?control=" + control.toAbsolutePath();
    }
}
