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

    /**
     * The ID of the transaction this writer opens for checkpoint {@code checkpointId}: {@code
     * <jobId>-<nodeId>-<subtask>-<checkpointId>}. It is the same across restarts of the run, so a
     * writer finds the transactions an earlier attempt left open by it.
     *
     * @throws UnsupportedOperationException on an engine without checkpoints
     */
    default String transactionId(long checkpointId) {
        throw new UnsupportedOperationException("this engine gives no transaction IDs");
    }

    /**
     * The checkpoint the writer was restored from, 0 when it starts fresh. On restore, a writer
     * that commits in two phases aborts the transactions of later checkpoints that an earlier
     * attempt left open ({@link #transactionId} with a higher checkpoint ID): they were never
     * committed and never will be. The committables of the restored checkpoint itself are committed
     * again by the engine, so committing must be idempotent.
     */
    default long restoredCheckpointId() {
        return 0;
    }

    /**
     * A directory on the local disk of this {@code writer} alone, for files it writes while it runs, such
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
