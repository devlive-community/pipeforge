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
     * Reports a row the target refused after {@link SinkWriter#write} returned, for example one row
     * of a batch that failed when the batch was flushed. The engine handles it with the dirty data
     * policy of the job, as if {@code write} had thrown {@link
     * org.devlive.pipeforge.api.row.BadRowException}: under {@code fail} this call throws and the job
     * fails; under {@code skip} the row is counted and the writer continues. Engines without a dirty
     * data policy throw.
     *
     * @param reason what is wrong with the row
     */
    default void reportBadRow(org.devlive.pipeforge.api.row.Row row, String reason) {
        throw new org.devlive.pipeforge.api.row.BadRowException(reason);
    }

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
