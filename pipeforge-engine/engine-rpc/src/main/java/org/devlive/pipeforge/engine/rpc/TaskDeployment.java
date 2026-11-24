/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Asks a task executor to run one task.
 *
 * @param id the task attempt
 * @param chain the operators of the task
 * @param parallelism the number of parallel instances of the vertex
 * @param settings runtime settings
 * @param run the run the task belongs to
 * @param allocationId the slot reservation the task runs in, or {@code null} where slots are not
 *     reserved (local runs)
 * @param inputs where the first node reads from when the chain has no source, one per input edge
 * @param restore the states the task starts from, {@link Restore#NONE} for a fresh start
 */
public record TaskDeployment(
        ExecutionAttemptId id,
        ChainDescriptor chain,
        int parallelism,
        TaskSettings settings,
        RunContext run,
        @org.jspecify.annotations.Nullable String allocationId,
        List<Input> inputs,
        Restore restore) {

    /**
     * Where a restarted task starts: the states of its nodes in a completed checkpoint of the run.
     *
     * @param checkpointId the checkpoint, 0 for none
     * @param states the states of every node by node id; a node that is not listed starts empty
     * @param directory where the state files are, relative to the root of the checkpoint storage;
     *     {@code null} for the run's own checkpoint {@code checkpointId}
     */
    public record Restore(
            long checkpointId,
            java.util.Map<String, List<StateHandle>> states,
            @org.jspecify.annotations.Nullable String directory) {
        /** A fresh start. */
        public static final Restore NONE = new Restore(0, java.util.Map.of());

        /** States of the run's own checkpoint {@code checkpointId}. */
        public Restore(long checkpointId, java.util.Map<String, List<StateHandle>> states) {
            this(checkpointId, states, null);
        }

        public Restore {
            if (checkpointId < 0 || (checkpointId == 0 && !states.isEmpty())) {
                throw new IllegalArgumentException("States need the checkpoint they come from");
            }
            java.util.Map<String, List<StateHandle>> copy = new java.util.LinkedHashMap<>();
            states.forEach((node, handles) -> copy.put(node, List.copyOf(handles)));
            states = java.util.Collections.unmodifiableMap(copy);
        }

        /** Whether the task restores from a checkpoint. */
        public boolean restores() {
            return checkpointId > 0;
        }

        /** The states of {@code node}, empty when it has none. */
        public List<StateHandle> of(String node) {
            return states.getOrDefault(node, List.of());
        }
    }

    /**
     * An input edge: one channel per upstream subpartition this task reads.
     *
     * @param index the position of the edge among the inputs of the first node
     * @param upstreamVertex the vertex that writes it
     */
    public record Input(int index, String upstreamVertex, List<Channel> channels) {
        public Input {
            channels = List.copyOf(channels);
            if (channels.isEmpty()) {
                throw new IllegalArgumentException("input " + index + " from " + upstreamVertex + " has no channels");
            }
        }
    }

    /**
     * A channel of an input.
     *
     * @param producer the task attempt that writes it
     * @param outputIndex the output of the producer
     * @param subpartition the subpartition of that output this task reads
     * @param producerAddress the data address of the producer's worker, {@code host:port}, or
     *     {@code null} when it runs on the same worker
     */
    public record Channel(
            ExecutionAttemptId producer,
            int outputIndex,
            int subpartition,
            @org.jspecify.annotations.Nullable String producerAddress) {}

    public TaskDeployment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(restore, "restore");
        inputs = List.copyOf(inputs);
        if (id.subtask() >= parallelism) {
            throw new IllegalArgumentException("Subtask " + id.subtask() + " is outside parallelism " + parallelism);
        }
        if (chain.hasSource() != inputs.isEmpty()) {
            throw new IllegalArgumentException(
                    chain.hasSource()
                            ? "Chain " + chain.vertexId() + " starts with a source and has no inputs"
                            : "Chain " + chain.vertexId() + " reads from the network and needs its inputs");
        }
    }

    /** When the run started: what {@code RUN_START_TIME()} returns in expressions. */
    public Instant runStartTime() {
        return run.runStart();
    }

    /** A deployment that starts fresh. */
    public TaskDeployment(
            ExecutionAttemptId id,
            ChainDescriptor chain,
            int parallelism,
            TaskSettings settings,
            RunContext run,
            @org.jspecify.annotations.Nullable String allocationId,
            List<Input> inputs) {
        this(id, chain, parallelism, settings, run, allocationId, inputs, Restore.NONE);
    }

    /** The same deployment, starting from {@code from}. */
    public TaskDeployment restoring(Restore from) {
        return new TaskDeployment(id, chain, parallelism, settings, run, allocationId, inputs, from);
    }

    /** A deployment of a chain that starts with a source. */
    public TaskDeployment(
            ExecutionAttemptId id,
            ChainDescriptor chain,
            int parallelism,
            TaskSettings settings,
            RunContext run,
            @org.jspecify.annotations.Nullable String allocationId) {
        this(id, chain, parallelism, settings, run, allocationId, List.of());
    }

    /** A deployment outside of reserved slots, as local runs deploy. */
    public TaskDeployment(
            ExecutionAttemptId id, ChainDescriptor chain, int parallelism, TaskSettings settings, RunContext run) {
        this(id, chain, parallelism, settings, run, null, List.of());
    }
}
