/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.time.Duration;
import java.util.Objects;

/**
 * Runtime settings of a task.
 *
 * @param asyncWriteBuffer whether the sink writes on its own thread behind a bounded queue
 * @param writeBatchSize rows per batch handed to the write thread
 * @param writeQueueCapacity batches the queue holds before the task thread blocks
 * @param cancelTimeout time a cancelled task gets to stop before its thread is interrupted; after
 *     twice this time it is given up
 */
public record TaskSettings(
        boolean asyncWriteBuffer, int writeBatchSize, int writeQueueCapacity, Duration cancelTimeout) {

    public TaskSettings {
        Objects.requireNonNull(cancelTimeout, "cancelTimeout");
        if (writeBatchSize < 1 || writeQueueCapacity < 1) {
            throw new IllegalArgumentException("writeBatchSize and writeQueueCapacity must be at least 1");
        }
    }

    public static TaskSettings defaults() {
        return new TaskSettings(true, 1024, 4, Duration.ofSeconds(30));
    }
}
