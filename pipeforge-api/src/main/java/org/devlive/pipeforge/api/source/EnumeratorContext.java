/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.factory.JobContext;

/** What an enumerator can do: see the registered readers and assign splits to them. */
@Stable
public interface EnumeratorContext<SplitT extends SourceSplit> {

    JobContext job();

    /** Number of parallel readers. */
    int parallelism();

    /** Readers that are currently registered. */
    Set<Integer> registeredReaders();

    /** Assigns splits to readers. */
    void assignSplits(Map<Integer, List<SplitT>> assignment);

    default void assignSplit(SplitT split, int readerId) {
        assignSplits(Map.of(readerId, List.of(split)));
    }

    /** Tells a reader that it will not get more splits. */
    void signalNoMoreSplits(int readerId);
}
