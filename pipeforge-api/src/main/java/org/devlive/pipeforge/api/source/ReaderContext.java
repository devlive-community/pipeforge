/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.factory.JobContext;

/** What a reader can do: identify itself, ask for splits and talk to its enumerator. */
@Stable
public interface ReaderContext {

    JobContext job();

    /** Index of this reader among {@link #parallelism()} readers, from 0. */
    int subtaskIndex();

    int parallelism();

    /** Asks the enumerator for another split (pull mode: fast readers get more work). */
    void sendSplitRequest();

    void sendReaderEvent(ReaderEvent event);

    /**
     * Tells the engine that the reader read split {@code splitId} to its end, for the progress of the
     * job: the split counts as read at once. A reader that does not tell has its splits counted as
     * read when its task finishes. Call it once per split, after the split's last row was emitted.
     */
    default void splitFinished(String splitId) {}

    /** The class loader of the plugin. */
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

    /**
     * A directory on the local disk of this {@code reader} alone, for files it writes while it runs, such
     * as spilled rows or a local buffer before an upload. It is created on the first call and deleted
     * with everything in it when the task ends, so it holds nothing across restarts: what must
     * survive goes into the checkpoint state. The engine counts it, with the task logs, against the
     * disk quota of the job on the worker ({@code worker.disk.quota-per-job}); a job over it fails
     * with {@code PF-RES-0004}.
     *
     * @throws UnsupportedOperationException on an engine without local work directories
     */
    @org.devlive.pipeforge.api.annotation.Evolving
    default java.nio.file.Path workDirectory() {
        throw new UnsupportedOperationException("this engine gives no work directory");
    }
}
