/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.time.Instant;

/**
 * A sample of the main counters of a run, for its history.
 *
 * @param at when it was taken
 * @param elapsedMs how long the run had run
 * @param rowsRead rows read
 * @param rowsWritten rows written
 * @param rowsDirty bad rows skipped
 * @param rowsFailed rows that failed
 */
public record MetricSample(
        Instant at, long elapsedMs, long rowsRead, long rowsWritten, long rowsDirty, long rowsFailed) {}
