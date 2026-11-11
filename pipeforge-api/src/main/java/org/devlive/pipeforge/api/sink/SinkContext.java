/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.sink;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.factory.JobContext;

/** Planning-time context of a sink. */
@Stable
public interface SinkContext {

    JobContext job();

    /** The name of the sink node, for error messages. */
    String nodeName();

    ClassLoader classLoader();
}
