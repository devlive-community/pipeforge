/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.checkpoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.rpc.CheckpointFiles;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.StateHandle;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.CheckpointMetadata;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.OperatorState;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.SubtaskState;
import org.jspecify.annotations.Nullable;

/**
 * A completed checkpoint or savepoint a run starts from, as its {@code _metadata} describes it.
 *
 * @param directory its directory, relative to the root of the checkpoint storage, ending in {@code /}
 * @param checkpointId its number
 * @param jobName the job that took it
 * @param enumerators the state of each source's enumerator, by node
 * @param states the states of each node's subtasks: node, then subtask, then its handles
 * @param parallelism the parallelism each node had
 * @param committables what the sinks prepared for it, committed again when a run starts from it
 */
public record RestorePoint(
        String directory,
        long checkpointId,
        String jobName,
        Map<String, SerializedValue> enumerators,
        Map<String, Map<Integer, List<StateHandle>>> states,
        Map<String, Integer> parallelism,
        List<SerializedValue> committables) {

    /** The options a run gives with its restore point. */
    public record Settings(String path, boolean allowNonRestoredState, boolean allowOtherJob) {
        public Settings {
            java.util.Objects.requireNonNull(path, "path");
        }
    }

    /**
     * Reads the restore point in {@code path}.
     *
     * @throws PipeForgeException {@code PF-CKPT-0004} when it cannot be read, {@code PF-CKPT-0002}
     *     when it is damaged or not complete
     */
    public static RestorePoint read(CheckpointStorage storage, String path) {
        String directory = path.endsWith("/") ? path : path + "/";
        byte[] file;
        try {
            if (!storage.isComplete(directory)) {
                throw PipeForgeException.of(ErrorCode.STATE_CORRUPTED)
                        .param("file", CheckpointFiles.METADATA)
                        .param("checkpoint", directory)
                        .param("reason", "it is not a completed checkpoint or savepoint")
                        .build();
            }
            file = storage.read(directory + CheckpointFiles.METADATA);
        } catch (IOException e) {
            throw unavailable(storage, directory, e);
        }
        CheckpointMetadata metadata = CheckpointFiles.readMetadata(file, directory);
        long id = metadata.getCheckpointId();
        Map<String, SerializedValue> enumerators = new LinkedHashMap<>();
        Map<String, Map<Integer, List<StateHandle>>> states = new LinkedHashMap<>();
        Map<String, Integer> parallelism = new LinkedHashMap<>();
        List<SerializedValue> committables = new ArrayList<>();
        for (OperatorState operator : metadata.getOperatorsList()) {
            String node = operator.getNodeId();
            String kind = operator.getOperatorKind();
            if (kind.equals("enumerator")) {
                SubtaskState subtask = operator.getSubtasks(0);
                enumerators.put(
                        node,
                        new SerializedValue(
                                subtask.getSerializerVersion(), body(storage, directory, id, node, subtask)));
                continue;
            }
            parallelism.merge(node, operator.getParallelism(), Math::max);
            for (SubtaskState subtask : operator.getSubtasksList()) {
                StateHandle.Location location = subtask.hasFile()
                        ? new StateHandle.File(subtask.getFile())
                        : new StateHandle.Inline(subtask.getInline().toByteArray());
                states.computeIfAbsent(node, n -> new LinkedHashMap<>())
                        .computeIfAbsent(subtask.getSubtask(), s -> new ArrayList<>())
                        .add(new StateHandle(
                                node,
                                kind,
                                subtask.getSerializerVersion(),
                                location,
                                subtask.getSize(),
                                subtask.getCrc32C()));
                if (kind.equals("committer")) {
                    for (byte[] element :
                            CheckpointFiles.readList(body(storage, directory, id, node, subtask), node, directory)) {
                        committables.add(new SerializedValue(subtask.getSerializerVersion(), element));
                    }
                }
            }
        }
        return new RestorePoint(directory, id, metadata.getJobName(), enumerators, states, parallelism, committables);
    }

    private static byte[] body(
            CheckpointStorage storage, String directory, long id, String node, SubtaskState subtask) {
        if (!subtask.hasFile()) {
            return subtask.getInline().toByteArray();
        }
        try {
            return CheckpointFiles.readState(storage.read(directory + subtask.getFile()), id, node, subtask.getFile());
        } catch (IOException e) {
            throw unavailable(storage, directory, e);
        }
    }

    private static PipeForgeException unavailable(CheckpointStorage storage, String directory, IOException e) {
        return PipeForgeException.of(ErrorCode.STORAGE_UNAVAILABLE)
                .param("location", storage.location())
                .param("reason", "reading " + directory + ": " + e.getMessage())
                .cause(e)
                .build();
    }

    /**
     * Checks that a job with {@code nodes} and their parallelism can start from this point.
     *
     * @param allowNonRestoredState whether states of nodes the job no longer has may be left out
     * @param allowOtherJob whether the point may come from a job of another name
     * @throws PipeForgeException {@code PF-CKPT-0003} otherwise
     */
    public void check(String job, Map<String, Integer> nodes, boolean allowNonRestoredState, boolean allowOtherJob) {
        if (!jobName.equals(job) && !allowOtherJob) {
            throw incompatible("it was taken by job '" + jobName + "', not '" + job + "'; allow another job to use it");
        }
        Set<String> stated = new java.util.TreeSet<>(states.keySet());
        stated.addAll(enumerators.keySet());
        for (String node : stated) {
            Integer wanted = nodes.get(node);
            if (wanted == null) {
                if (!allowNonRestoredState) {
                    throw incompatible("it holds state of node '" + node
                            + "', which the job does not have; allow non-restored state to leave it out");
                }
                continue;
            }
            Integer had = parallelism.get(node);
            if (had != null && had != (int) wanted) {
                throw incompatible("node '" + node + "' had parallelism " + had + " and has " + wanted
                        + "; a run cannot change the parallelism of a node with state yet");
            }
        }
    }

    private PipeForgeException incompatible(String reason) {
        return PipeForgeException.of(ErrorCode.STATE_INCOMPATIBLE)
                .param("file", CheckpointFiles.METADATA)
                .param("checkpoint", directory)
                .param("reason", reason)
                .build();
    }

    /** The states of subtask {@code subtask} of {@code nodes}, by node, for a task to restore. */
    public Map<String, List<StateHandle>> of(List<String> nodes, int subtask) {
        Map<String, List<StateHandle>> result = new LinkedHashMap<>();
        for (String node : nodes) {
            List<StateHandle> handles = states.getOrDefault(node, Map.of()).get(subtask);
            if (handles != null) {
                result.put(node, handles);
            }
        }
        return result;
    }

    /** The enumerator state of {@code node}, or {@code null} without one. */
    public @Nullable SerializedValue enumerator(String node) {
        return enumerators.get(node);
    }
}
