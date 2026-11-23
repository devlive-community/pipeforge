/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import java.util.Collection;
import java.util.Map;
import org.devlive.pipeforge.api.annotation.Evolving;

/**
 * Sink writer capability: records how far the source's splits were written, in the target and
 * together with the rows, so that a restart after a failure reads on from there instead of
 * writing the rows since the last checkpoint again (design 06 section 5.4). The engine uses it when
 * the source and the sink run in one task.
 *
 * <ol>
 *   <li>{@link #startProgress} once the writer is created;
 *   <li>{@link #progress} in the order of the rows: every row of the split up to the position was
 *       written before; the writer commits the position in the same transaction as those rows, and
 *       never commits rows of a split after its last position without a later one;
 *   <li>{@link #recordedProgress} when the task starts again, for the splits it continues;
 *   <li>{@link #forgetProgress} once the input ended and everything was written.
 * </ol>
 */
@Evolving
public interface SupportsProgress {

    /**
     * Prepares the progress of a run's node, for example a table in the target.
     *
     * @return whether the writer keeps progress; {@code false} when it cannot here, for example
     *     without the permission to create its table
     */
    boolean startProgress(String runId, String nodeId) throws Exception;

    /** Every row of {@code splitId} up to {@code position} was handed to the writer. */
    void progress(String splitId, String position) throws Exception;

    /** The last positions committed for these splits; a split without any is left out. */
    Map<String, String> recordedProgress(Collection<String> splitIds) throws Exception;

    /** The input ended and was written: the recorded progress of this writer is not needed any more. */
    default void forgetProgress() throws Exception {}
}
