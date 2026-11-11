/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;

/**
 * Where a reader emits its data. Readers with event time emit timestamps and use {@link
 * #forSplit(String)} so that the engine tracks watermarks per split.
 */
@Stable
public interface ReaderOutput {

    void collect(Row row);

    /** Emits a row with its event time in epoch milliseconds. */
    default void collect(Row row, long eventTimestamp) {
        collect(row);
    }

    void collect(SchemaChangeEvent event);

    /** An output for the rows of one split; the default returns this output. */
    default ReaderOutput forSplit(String splitId) {
        return this;
    }

    /** Releases the per-split output once the split is finished. */
    default void releaseSplit(String splitId) {}
}
