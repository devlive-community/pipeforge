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
 * Reads the splits assigned to one parallel instance.
 *
 * <p>{@link #pollNext} must not block for long: return {@link PollStatus#NOTHING_AVAILABLE} when
 * no data is ready. The state of a split must record the position that has been handed to the
 * output, so that a restored reader continues exactly there.
 */
@Stable
public interface SourceReader<SplitT extends SourceSplit> extends AutoCloseable {

    void start() throws Exception;

    void addSplits(List<SplitT> splits);

    /** No more splits will be assigned; return END_OF_INPUT once the current ones are done. */
    void notifyNoMoreSplits();

    PollStatus pollNext(ReaderOutput output) throws Exception;

    /** The unfinished splits with their progress. */
    List<SplitT> snapshotState(long checkpointId);

    default void notifyCheckpointComplete(long checkpointId) throws Exception {}

    /** Interrupts a blocking read, e.g. {@code Statement.cancel()}; called from another thread. */
    default void cancel() {}

    @Override
    default void close() throws Exception {}
}
