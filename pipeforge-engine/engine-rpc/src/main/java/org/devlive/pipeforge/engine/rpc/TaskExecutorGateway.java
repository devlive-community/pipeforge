/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * What a job master can ask of a worker's task executor. Calls are asynchronous: the future
 * completes when the executor has accepted the request, not when the task has acted on it.
 */
public interface TaskExecutorGateway {

    CompletableFuture<Void> deployTask(TaskDeployment deployment);

    /** Cancels a task; it reports {@link TaskState#CANCELED} once it has stopped. Unknown tasks are ignored. */
    CompletableFuture<Void> cancelTask(ExecutionAttemptId task);

    /** Delivers an event to an operator of a task; events for unknown tasks are dropped. */
    CompletableFuture<Void> sendOperatorEvent(ExecutionAttemptId task, OperatorEvent event);

    /**
     * Reserves a slot for a job. A reservation that receives no task within 60 seconds is released
     * by the worker; reserving again with the same allocation succeeds. Fails with {@code
     * PF-RES-0007} when another allocation holds the slot.
     */
    CompletableFuture<Void> requestSlot(SlotRequest request);

    /** Releases the slot of an allocation; unknown allocations are ignored. */
    CompletableFuture<Void> freeSlot(String allocationId);

    /**
     * Reads the log of a task, also after the task has ended. Without {@link LogQuery#follow()} one
     * chunk arrives; with it, chunks arrive as the task writes until it ends. The future completes
     * after the last chunk; cancelling it stops following. Fails with {@code PF-CLUS-0012} when the
     * worker has no log of the task.
     */
    default CompletableFuture<Void> readLog(ExecutionAttemptId task, LogQuery query, Consumer<LogChunk> chunks) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("this task executor keeps no task logs"));
    }
}
