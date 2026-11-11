/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import java.util.List;
import org.devlive.pipeforge.api.annotation.Stable;

/**
 * Discovers splits and hands them out. There is one enumerator per source, running in the
 * coordinator; readers pull splits with {@link #handleSplitRequest(int)}.
 */
@Stable
public interface SplitEnumerator<SplitT extends SourceSplit, StateT> extends AutoCloseable {

    void start();

    /** A reader asks for its next split. */
    void handleSplitRequest(int readerId);

    /** Splits of a failed reader come back for reassignment. */
    void addSplitsBack(List<SplitT> splits, int readerId);

    void addReader(int readerId);

    StateT snapshotState(long checkpointId) throws Exception;

    default void notifyCheckpointComplete(long checkpointId) throws Exception {}

    default void handleReaderEvent(int readerId, ReaderEvent event) {}

    @Override
    default void close() throws Exception {}
}
