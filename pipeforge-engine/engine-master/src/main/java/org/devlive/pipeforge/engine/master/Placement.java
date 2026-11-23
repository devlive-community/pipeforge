/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.jspecify.annotations.Nullable;

/**
 * Where the tasks of a job run: the slots a job master deploys into. Subtask i of every vertex runs
 * in slot i. In one process there is one task executor and nothing to reserve; on a cluster the
 * {@link Scheduler} reserves slots on the workers.
 */
public interface Placement {

    /**
     * One slot of a job.
     *
     * @param address the RPC address of the worker
     * @param dataAddress where other tasks read the outputs of tasks in this slot, {@code host:port}
     * @param allocationId the reservation, or {@code null} where slots are not reserved
     */
    record Slot(
            int index,
            String workerId,
            String address,
            String dataAddress,
            TaskExecutorGateway gateway,
            @Nullable String allocationId) {}

    /** Gets {@code count} slots for a run; completes when they are reserved. */
    CompletableFuture<List<Slot>> acquire(String jobId, String runId, int count);

    /**
     * Gets new slots in place of slots whose worker was lost; the slots keep their indexes.
     * {@link #release} gives them back with the others.
     */
    CompletableFuture<List<Slot>> replace(String jobId, String runId, List<Integer> indexes);

    /** Gives the slots back once the run has ended. */
    void release();

    /** Every slot on the task executor of this process, without reservations. */
    static Placement local(TaskExecutorGateway executor) {
        return new Placement() {
            @Override
            public CompletableFuture<List<Slot>> acquire(String jobId, String runId, int count) {
                List<Slot> slots = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    slots.add(new Slot(i, "local", "local", "local", executor, null));
                }
                return CompletableFuture.completedFuture(slots);
            }

            @Override
            public CompletableFuture<List<Slot>> replace(String jobId, String runId, List<Integer> indexes) {
                return CompletableFuture.completedFuture(indexes.stream()
                        .map(i -> new Slot(i, "local", "local", "local", executor, null))
                        .toList());
            }

            @Override
            public void release() {
                // nothing was reserved
            }
        };
    }

    /**
     * Slots that {@code scheduler} reserves on the workers of a cluster.
     *
     * @param labels the labels every worker of the job must carry
     */
    static Placement cluster(
            Scheduler scheduler,
            Scheduler.Workers workers,
            Map<String, String> labels,
            Scheduler.Strategy strategy,
            java.time.Duration timeout) {
        AtomicReference<Scheduler.Allocation> held = new AtomicReference<>();
        List<Scheduler.Allocation> replacements = new java.util.concurrent.CopyOnWriteArrayList<>();
        return new Placement() {
            @Override
            public CompletableFuture<List<Slot>> acquire(String jobId, String runId, int count) {
                return scheduler
                        .allocate(jobId, runId, new Scheduler.Requirements(count, labels, strategy, timeout))
                        .thenApply(allocation -> {
                            held.set(allocation);
                            List<Slot> slots = new ArrayList<>();
                            for (Scheduler.Assignment assignment : allocation.slots()) {
                                slots.add(new Slot(
                                        assignment.index(),
                                        assignment.worker().workerId(),
                                        assignment.worker().rpcAddress(),
                                        assignment.worker().dataAddress(),
                                        workers.gateway(assignment.worker()),
                                        assignment.allocationId()));
                            }
                            return slots;
                        });
            }

            @Override
            public CompletableFuture<List<Slot>> replace(String jobId, String runId, List<Integer> indexes) {
                return scheduler
                        .allocate(jobId, runId, new Scheduler.Requirements(indexes.size(), labels, strategy, timeout))
                        .thenApply(allocation -> {
                            replacements.add(allocation);
                            List<Slot> slots = new ArrayList<>();
                            for (int i = 0; i < allocation.slots().size(); i++) {
                                Scheduler.Assignment assignment =
                                        allocation.slots().get(i);
                                slots.add(new Slot(
                                        indexes.get(i),
                                        assignment.worker().workerId(),
                                        assignment.worker().rpcAddress(),
                                        assignment.worker().dataAddress(),
                                        workers.gateway(assignment.worker()),
                                        assignment.allocationId()));
                            }
                            return slots;
                        });
            }

            @Override
            public void release() {
                Scheduler.Allocation allocation = held.getAndSet(null);
                if (allocation != null) {
                    scheduler.release(allocation);
                }
                for (Scheduler.Allocation replacement : replacements) {
                    scheduler.release(replacement);
                }
                replacements.clear();
            }
        };
    }
}
