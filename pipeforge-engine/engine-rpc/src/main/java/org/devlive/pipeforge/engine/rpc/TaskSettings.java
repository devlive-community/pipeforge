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
 * @param metricsInterval how often the task reports its metrics to the job master
 * @param skipDirtyRows whether bad rows are skipped and counted ({@code env.dirty.policy: skip});
 *     otherwise the first bad row fails the task
 */
public record TaskSettings(
        boolean asyncWriteBuffer,
        int writeBatchSize,
        int writeQueueCapacity,
        Duration cancelTimeout,
        Duration metricsInterval,
        boolean skipDirtyRows) {

    public static final Duration DEFAULT_METRICS_INTERVAL = Duration.ofSeconds(1);

    public TaskSettings {
        Objects.requireNonNull(cancelTimeout, "cancelTimeout");
        Objects.requireNonNull(metricsInterval, "metricsInterval");
        if (metricsInterval.toMillis() < 1) {
            throw new IllegalArgumentException("metricsInterval must be at least 1 ms");
        }
        if (writeBatchSize < 1 || writeQueueCapacity < 1) {
            throw new IllegalArgumentException("writeBatchSize and writeQueueCapacity must be at least 1");
        }
    }

    /** Settings that fail at the first bad row. */
    public TaskSettings(
            boolean asyncWriteBuffer,
            int writeBatchSize,
            int writeQueueCapacity,
            Duration cancelTimeout,
            Duration metricsInterval) {
        this(asyncWriteBuffer, writeBatchSize, writeQueueCapacity, cancelTimeout, metricsInterval, false);
    }

    public TaskSettings withSkipDirtyRows(boolean skip) {
        return new TaskSettings(
                asyncWriteBuffer, writeBatchSize, writeQueueCapacity, cancelTimeout, metricsInterval, skip);
    }

    public static TaskSettings defaults() {
        return new TaskSettings(true, 1024, 4, Duration.ofSeconds(30), DEFAULT_METRICS_INTERVAL);
    }
}
