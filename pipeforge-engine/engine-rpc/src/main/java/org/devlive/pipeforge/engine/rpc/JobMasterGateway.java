/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.concurrent.CompletableFuture;

/**
 * What a task can tell the master. Messages carry the task's {@link ExecutionAttemptId}, so one
 * gateway serves every job: the master routes them to the job master of the run.
 */
public interface JobMasterGateway {

    CompletableFuture<Void> updateTaskState(TaskStateUpdate update);

    CompletableFuture<Void> sendReaderMessage(ReaderMessage message);

    /** The current metrics of a running task, sent periodically; the final ones come with its final state. */
    CompletableFuture<Void> reportMetrics(ExecutionAttemptId task, TaskMetricsSnapshot metrics);

    /** A task took its part of a checkpoint. */
    CompletableFuture<Void> acknowledgeCheckpoint(CheckpointAck ack);

    /** A task cannot take part in a checkpoint, for {@code reason}. */
    CompletableFuture<Void> declineCheckpoint(
            ExecutionAttemptId task, long checkpointId, org.devlive.pipeforge.common.error.PipeForgeException reason);
}
