/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import java.util.LinkedHashMap;
import java.util.Map;
import org.devlive.pipeforge.dist.config.Configuration;
import org.devlive.pipeforge.dist.config.Settings;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorages;
import org.jspecify.annotations.Nullable;

/** The checkpoint storage of a process: opened from {@code checkpoint.*} and checked at the start. */
final class CheckpointSetup {

    private CheckpointSetup() {}

    /**
     * The storage of {@code checkpoint.dir}, checked to take a file, or {@code null} when it is not
     * set.
     *
     * @param who the process, for the probe's file name
     * @throws org.devlive.pipeforge.common.error.PipeForgeException {@code PF-CKPT-0004} when it
     *     cannot be used
     */
    static @Nullable CheckpointStorage open(Configuration config, String who) {
        String location = config.optional(Settings.CHECKPOINT_DIR).orElse("").strip();
        if (location.isEmpty()) {
            return null;
        }
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("s3.endpoint", config.string(Settings.CHECKPOINT_S3_ENDPOINT));
        settings.put("s3.region", config.string(Settings.CHECKPOINT_S3_REGION));
        settings.put("s3.path-style", String.valueOf(config.bool(Settings.CHECKPOINT_S3_PATH_STYLE)));
        settings.put("s3.access-key", config.string(Settings.CHECKPOINT_S3_ACCESS_KEY));
        settings.put("s3.secret-key", config.string(Settings.CHECKPOINT_S3_SECRET_KEY));
        CheckpointStorage storage = CheckpointStorages.open(location, settings);
        try {
            CheckpointStorages.probe(storage, who);
        } catch (RuntimeException e) {
            try {
                storage.close();
            } catch (Exception closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
        return storage;
    }

    static void close(@Nullable CheckpointStorage storage) {
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception e) {
                // closing a client; nothing is lost
            }
        }
    }
}
