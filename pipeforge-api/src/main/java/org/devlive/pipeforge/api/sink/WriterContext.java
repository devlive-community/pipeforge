/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.sink;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.factory.JobContext;

/** Runtime context of a sink writer. */
@Stable
public interface WriterContext {

    JobContext job();

    int subtaskIndex();

    int parallelism();

    ClassLoader classLoader();
}
