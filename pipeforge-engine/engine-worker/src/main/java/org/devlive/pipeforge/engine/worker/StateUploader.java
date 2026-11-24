/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32C;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointPaths;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.rpc.CheckpointFiles;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.StateHandle;
import org.jspecify.annotations.Nullable;

/**
 * Writes the states of a checkpoint on threads of its own, so that the task goes on with its rows
 * meanwhile (checkpoint design, section 3.3). A state up to the inline threshold travels in the
 * acknowledgement; a larger one becomes a file of the checkpoint's directory, {@code
 * <job>/chk-<n>/<node>-<subtask>.state}. A savepoint's files are written there too; the master
 * copies them into the savepoint.
 */
final class StateUploader implements AutoCloseable {

    /** A state taken on the task thread, to be written. */
    record Pending(String nodeId, String kind, int subtask, int serializerVersion, byte[] body) {}

    private final @Nullable CheckpointStorage storage;
    private final int inlineThreshold;
    private final ExecutorService threads;

    StateUploader(@Nullable CheckpointStorage storage, int inlineThreshold, String worker) {
        this.storage = storage;
        this.inlineThreshold = inlineThreshold;
        this.threads = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "state-upload-" + worker);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Writes the states; completes with their handles, or with why they could not be written. */
    CompletableFuture<List<StateHandle>> upload(ExecutionAttemptId task, long checkpointId, List<Pending> states) {
        if (states.stream().allMatch(s -> CheckpointFiles.inline(s.body().length, inlineThreshold))) {
            List<StateHandle> handles = new ArrayList<>();
            states.forEach(s -> handles.add(handle(s, new StateHandle.Inline(s.body()))));
            return CompletableFuture.completedFuture(handles);
        }
        return CompletableFuture.supplyAsync(() -> write(task, checkpointId, states), threads);
    }

    private List<StateHandle> write(ExecutionAttemptId task, long checkpointId, List<Pending> states) {
        List<StateHandle> handles = new ArrayList<>();
        for (Pending state : states) {
            if (CheckpointFiles.inline(state.body().length, inlineThreshold)) {
                handles.add(handle(state, new StateHandle.Inline(state.body())));
                continue;
            }
            CheckpointStorage target = storage;
            if (target == null) {
                throw new CompletionException(failed(
                        checkpointId,
                        "the state of " + state.nodeId() + " is " + state.body().length
                                + " bytes, and this worker has no checkpoint storage (checkpoint.dir)"));
            }
            String file = state.kind().equals("committer")
                    ? CheckpointFiles.committableFile(state.nodeId(), state.subtask())
                    : CheckpointFiles.stateFile(state.nodeId(), state.subtask());
            try {
                target.write(
                        CheckpointPaths.checkpoint(task.runId(), checkpointId) + file,
                        CheckpointFiles.writeState(checkpointId, state.nodeId(), state.body()));
            } catch (IOException | RuntimeException e) {
                throw new CompletionException(
                        failed(checkpointId, "writing " + file + " to " + target.location() + ": " + e.getMessage()));
            }
            handles.add(handle(state, new StateHandle.File(file)));
        }
        return handles;
    }

    /**
     * The elements of a state a restarted task restores: from the handle when it is inline, else
     * from its file in {@code directory} of checkpoint {@code checkpointId}, checked against the
     * handle's size and CRC32C.
     */
    List<byte[]> read(String directory, long checkpointId, StateHandle handle) {
        String checkpoint = String.valueOf(checkpointId);
        byte[] body;
        String name;
        switch (handle.location()) {
            case StateHandle.Inline inline -> {
                body = inline.bytes();
                name = handle.nodeId() + " (" + handle.operatorKind() + ")";
            }
            case StateHandle.File file -> {
                name = file.path();
                CheckpointStorage source = storage;
                if (source == null) {
                    throw PipeForgeException.of(ErrorCode.STORAGE_UNAVAILABLE)
                            .param("location", "checkpoint.dir")
                            .param("reason", "this worker has no checkpoint storage to read " + name + " from")
                            .build();
                }
                byte[] whole;
                try {
                    whole = source.read(directory + file.path());
                } catch (IOException | RuntimeException e) {
                    throw PipeForgeException.of(ErrorCode.STORAGE_UNAVAILABLE)
                            .param("location", source.location())
                            .param(
                                    "reason",
                                    "reading " + name + " of checkpoint " + checkpointId + ": " + e.getMessage())
                            .cause(e)
                            .build();
                }
                body = CheckpointFiles.readState(whole, checkpointId, handle.nodeId(), name);
            }
        }
        CRC32C crc = new CRC32C();
        crc.update(body);
        if (body.length != handle.size() || (int) crc.getValue() != handle.crc32c()) {
            throw PipeForgeException.of(ErrorCode.STATE_CORRUPTED)
                    .param("file", name)
                    .param("checkpoint", checkpoint)
                    .param("reason", "its size or checksum differs from what the checkpoint recorded")
                    .build();
        }
        return CheckpointFiles.readList(body, name, checkpoint);
    }

    private static StateHandle handle(Pending state, StateHandle.Location location) {
        CRC32C crc = new CRC32C();
        crc.update(state.body());
        return new StateHandle(
                state.nodeId(), state.kind(), state.serializerVersion(), location, state.body().length, (int)
                        crc.getValue());
    }

    private static PipeForgeException failed(long checkpointId, String reason) {
        return PipeForgeException.of(ErrorCode.CHECKPOINT_FAILED)
                .param("checkpoint", checkpointId)
                .param("reason", reason)
                .build();
    }

    @Override
    public void close() {
        threads.shutdownNow();
    }
}
