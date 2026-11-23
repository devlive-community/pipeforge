/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;
import org.devlive.pipeforge.api.source.SourceSplit;

/**
 * Source capability: continues a split after a position its reader emitted with {@link
 * org.devlive.pipeforge.api.source.ReaderOutput#progress}. A restarted task uses it with the
 * position a sink recorded (see {@link SupportsProgress}), when that is further than the split's own.
 */
@Evolving
public interface SupportsResume<SplitT extends SourceSplit> {

    /**
     * The split continued after {@code position}, or {@code split} itself when it is already there
     * or further, or cannot resume there.
     */
    SplitT resume(SplitT split, String position) throws Exception;
}
