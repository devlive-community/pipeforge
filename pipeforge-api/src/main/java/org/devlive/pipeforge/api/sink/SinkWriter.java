/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.sink;

import java.util.List;
import java.util.Optional;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;

/** Writes the rows of one parallel sink instance. */
@Stable
public interface SinkWriter<CommT, StateT> extends AutoCloseable {

    void write(Row row) throws Exception;

    default void applySchemaChange(SchemaChangeEvent event) throws Exception {
        throw new UnsupportedSchemaChangeException(event);
    }

    /** Writes buffered rows out; they are not necessarily committed. */
    void flush() throws Exception;

    /** Pre-commit of two-phase commit: what the committer commits after the checkpoint. */
    default Optional<CommT> prepareCommit(long checkpointId) throws Exception {
        flush();
        return Optional.empty();
    }

    default List<StateT> snapshotState(long checkpointId) throws Exception {
        return List.of();
    }

    /** Whether a failed batch leaves no rows behind, so that it can be split to find bad rows. */
    default boolean failedWritesLeaveNoRows() {
        return false;
    }

    @Override
    default void close() throws Exception {}
}
