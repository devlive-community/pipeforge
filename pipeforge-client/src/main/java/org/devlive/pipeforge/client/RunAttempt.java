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
 * An attempt of a run.
 *
 * @param attempt its number, from 1
 * @param status its state
 * @param startedAt when it started
 * @param endedAt when it ended
 * @param error why it failed
 * @param restorePath the checkpoint it started from
 * @param retryAt when the next attempt starts, while the run waits to be retried
 * @param rowsRead rows it read, as of its last metrics sample
 * @param rowsWritten rows it wrote, as of its last metrics sample
 * @param rowsDirty bad rows it skipped, as of its last metrics sample
 * @param elapsedMs how long it has been running, as of its last metrics sample
 */
public record RunAttempt(
        int attempt,
        String status,
        Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable RunError error,
        @Nullable String restorePath,
        @Nullable Instant retryAt,
        @Nullable Long rowsRead,
        @Nullable Long rowsWritten,
        @Nullable Long rowsDirty,
        @Nullable Long elapsedMs) {}
