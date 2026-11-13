/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Engine settings of one submission.
 *
 * @param asyncWriteBuffer whether sinks write on their own thread, behind a bounded queue, so that
 *     reading and writing overlap
 * @param writeBatchSize rows per batch handed to the write thread
 * @param writeQueueCapacity batches the queue holds before the reading side blocks (backpressure)
 * @param cancelTimeout how long a cancelled task may take to stop before its thread is interrupted;
 *     after twice this time the task is given up with {@code PF-RES-0005}
 * @param metricsInterval how often tasks report their metrics and the job metrics are refreshed
 * @param enumeratorTimeout how long one call of the split enumerator may take before the job fails
 *     with {@code PF-SRC-0004}
 */
public record SubmitOptions(
        boolean asyncWriteBuffer,
        int writeBatchSize,
        int writeQueueCapacity,
        Duration cancelTimeout,
        Duration metricsInterval,
        Duration enumeratorTimeout) {

    public static final int DEFAULT_WRITE_BATCH_SIZE = 1024;
    public static final int DEFAULT_WRITE_QUEUE_CAPACITY = 4;
    public static final Duration DEFAULT_CANCEL_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_METRICS_INTERVAL = Duration.ofSeconds(1);
    public static final Duration DEFAULT_ENUMERATOR_TIMEOUT = Duration.ofSeconds(60);

    public SubmitOptions {
        Objects.requireNonNull(cancelTimeout, "cancelTimeout");
        Objects.requireNonNull(metricsInterval, "metricsInterval");
        if (metricsInterval.toMillis() < 1) {
            throw new IllegalArgumentException("metricsInterval must be at least 1 ms");
        }
        if (writeBatchSize < 1) {
            throw new IllegalArgumentException("writeBatchSize must be at least 1");
        }
        if (writeQueueCapacity < 1) {
            throw new IllegalArgumentException("writeQueueCapacity must be at least 1");
        }
        if (cancelTimeout.isNegative() || cancelTimeout.isZero()) {
            throw new IllegalArgumentException("cancelTimeout must be positive");
        }
        Objects.requireNonNull(enumeratorTimeout, "enumeratorTimeout");
        if (enumeratorTimeout.isNegative() || enumeratorTimeout.isZero()) {
            throw new IllegalArgumentException("enumeratorTimeout must be positive");
        }
    }

    /** Options with the default enumerator timeout. */
    public SubmitOptions(
            boolean asyncWriteBuffer,
            int writeBatchSize,
            int writeQueueCapacity,
            Duration cancelTimeout,
            Duration metricsInterval) {
        this(
                asyncWriteBuffer,
                writeBatchSize,
                writeQueueCapacity,
                cancelTimeout,
                metricsInterval,
                DEFAULT_ENUMERATOR_TIMEOUT);
    }

    public static SubmitOptions defaults() {
        return new SubmitOptions(
                true,
                DEFAULT_WRITE_BATCH_SIZE,
                DEFAULT_WRITE_QUEUE_CAPACITY,
                DEFAULT_CANCEL_TIMEOUT,
                DEFAULT_METRICS_INTERVAL);
    }

    public SubmitOptions withAsyncWriteBuffer(boolean value) {
        return new SubmitOptions(
                value, writeBatchSize, writeQueueCapacity, cancelTimeout, metricsInterval, enumeratorTimeout);
    }

    public SubmitOptions withWriteBatchSize(int value) {
        return new SubmitOptions(
                asyncWriteBuffer, value, writeQueueCapacity, cancelTimeout, metricsInterval, enumeratorTimeout);
    }

    public SubmitOptions withWriteQueueCapacity(int value) {
        return new SubmitOptions(
                asyncWriteBuffer, writeBatchSize, value, cancelTimeout, metricsInterval, enumeratorTimeout);
    }

    public SubmitOptions withMetricsInterval(Duration value) {
        return new SubmitOptions(
                asyncWriteBuffer, writeBatchSize, writeQueueCapacity, cancelTimeout, value, enumeratorTimeout);
    }

    public SubmitOptions withCancelTimeout(Duration value) {
        return new SubmitOptions(
                asyncWriteBuffer, writeBatchSize, writeQueueCapacity, value, metricsInterval, enumeratorTimeout);
    }

    public SubmitOptions withEnumeratorTimeout(Duration value) {
        return new SubmitOptions(
                asyncWriteBuffer, writeBatchSize, writeQueueCapacity, cancelTimeout, metricsInterval, value);
    }
}
