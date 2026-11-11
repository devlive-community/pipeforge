/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The validated configuration of a fake source.
 *
 * @param tables the tables, in the order written
 * @param splits splits per table
 * @param seed seed of the generated values
 * @param nullRatio share of NULL values in nullable non-key columns
 * @param badRowRatio share of rows that break a column type
 * @param rowsPerSecond the rate limit of the whole source; 0 for none
 * @param unbounded whether rows are generated forever
 * @param eventTime the event time settings, or {@code null}
 */
record FakeSettings(
        List<FakeTable> tables,
        int splits,
        long seed,
        double nullRatio,
        double badRowRatio,
        double rowsPerSecond,
        boolean unbounded,
        @Nullable EventTime eventTime) {

    FakeSettings {
        tables = List.copyOf(tables);
    }

    /**
     * @param column the event time column
     * @param start the event time of row 0
     * @param step the advance per row
     * @param maxOutOfOrder the largest random delay
     */
    record EventTime(String column, Instant start, Duration step, Duration maxOutOfOrder) {}
}
