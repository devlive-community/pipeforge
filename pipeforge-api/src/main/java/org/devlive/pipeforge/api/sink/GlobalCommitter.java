/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.sink;

import java.util.List;
import org.devlive.pipeforge.api.annotation.Stable;

/** Combines the committables of all writers into one commit, e.g. a table snapshot. */
@Stable
public interface GlobalCommitter<CommT, GlobalT> {

    GlobalT combine(List<CommT> committables);

    void commit(List<GlobalT> globals) throws Exception;
}
