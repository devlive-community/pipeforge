/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A run of a job: its state, times and the rows it read and wrote as of its last metrics sample.
 *
 * @param id the run ID
 * @param job the name of the job
 * @param status QUEUE, RUNNING, PAUSING, PAUSED, CANCELLING, RETRY_WAIT, SUCCESS, FAILED, CANCELLED or
 *     TIMEOUT
 * @param submittedBy who submitted it, when known
 * @param submittedAt when it was submitted
 * @param startedAt when it started
 * @param endedAt when it ended
 * @param updatedAt when its state last changed
 * @param error why it did not succeed
 * @param rowsRead rows read, as of the last metrics sample
 * @param rowsWritten rows written, as of the last metrics sample
 */
public record Run(
        String id,
        String job,
        String status,
        @Nullable String submittedBy,
        Instant submittedAt,
        @Nullable Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable Instant updatedAt,
        @Nullable RunError error,
        @Nullable Long rowsRead,
        @Nullable Long rowsWritten) {}
