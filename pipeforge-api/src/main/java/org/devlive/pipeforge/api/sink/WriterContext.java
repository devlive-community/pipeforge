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

    /**
     * Where the plugin gets its own threads, for example to fetch or write in the background. The
     * engine names them after the task, gives them the plugin's class loader, and interrupts them
     * when the task ends; a thread still alive after that is reported as leaked. An uncaught
     * exception in one of them fails the task. Threads started any other way are not stopped.
     */
    default java.util.concurrent.ThreadFactory threadFactory() {
        return java.util.concurrent.Executors.defaultThreadFactory();
    }
}
