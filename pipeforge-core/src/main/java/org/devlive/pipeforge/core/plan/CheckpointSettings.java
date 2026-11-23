/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.time.Duration;
import org.devlive.pipeforge.core.job.JobMode;

/**
 * How a job takes checkpoints ({@code env.checkpoint.*}); they are taken only on a cluster with
 * {@code checkpoint.dir}.
 *
 * @param interval the time from one checkpoint to the next
 * @param timeout how long a checkpoint may take before it fails
 * @param minPause the least time from the end of one checkpoint to the start of the next
 * @param maxConcurrent the checkpoints that may be in progress at once
 * @param tolerableFailures the checkpoints that may fail in a row before the run fails
 * @param retained the completed checkpoints kept; older ones are deleted
 * @param externalized what happens to the checkpoints of a run that is cancelled
 */
public record CheckpointSettings(
        boolean enabled,
        Duration interval,
        Duration timeout,
        Duration minPause,
        int maxConcurrent,
        int tolerableFailures,
        int retained,
        Externalized externalized) {

    /** What happens to the checkpoints of a cancelled run; a failed run always keeps them. */
    public enum Externalized {
        RETAIN_ON_CANCELLATION,
        DELETE_ON_CANCELLATION
    }

    public CheckpointSettings {
        if (interval.isNegative() || interval.isZero() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("The checkpoint interval and timeout must be positive");
        }
        if (minPause.isNegative() || maxConcurrent < 1 || tolerableFailures < 0 || retained < 1) {
            throw new IllegalArgumentException("Invalid checkpoint settings");
        }
    }

    /** The interval when the job sets none: a minute for streaming jobs, five for batch jobs. */
    public static Duration defaultInterval(JobMode mode) {
        return mode == JobMode.STREAMING ? Duration.ofMinutes(1) : Duration.ofMinutes(5);
    }

    /** The settings of a job that sets none. */
    public static CheckpointSettings defaults(JobMode mode) {
        return new CheckpointSettings(
                true,
                defaultInterval(mode),
                Duration.ofMinutes(10),
                Duration.ZERO,
                1,
                3,
                3,
                Externalized.RETAIN_ON_CANCELLATION);
    }
}
