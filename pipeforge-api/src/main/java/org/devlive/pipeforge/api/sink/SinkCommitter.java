/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.sink;

import java.util.List;
import org.devlive.pipeforge.api.annotation.Stable;

/** Commits the committables of all writers after a checkpoint completes. */
@Stable
public interface SinkCommitter<CommT> {

    /** Commits; returns the committables that must be retried. */
    List<CommT> commit(List<CommT> committables) throws Exception;

    void abort(List<CommT> committables) throws Exception;
}
