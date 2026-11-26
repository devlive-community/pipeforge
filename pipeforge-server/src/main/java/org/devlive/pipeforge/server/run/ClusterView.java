/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The cluster as the master sees it: the master and its registered workers, with their slots.
 *
 * @param slots the slots of all workers
 * @param slotsUsed the slots a run holds
 * @param usage {@code slotsUsed / slots}, from 0 to 1; 0 without slots
 */
public record ClusterView(Master master, List<Worker> workers, int slots, int slotsUsed, double usage) {

    public ClusterView {
        workers = List.copyOf(workers);
    }

    /** The cluster of {@code master} and {@code workers}, with the totals. */
    public static ClusterView of(Master master, List<Worker> workers) {
        int slots = workers.stream().mapToInt(w -> w.slots().size()).sum();
        int used = workers.stream().mapToInt(Worker::slotsUsed).sum();
        return new ClusterView(master, workers, slots, used, slots == 0 ? 0 : (double) used / slots);
    }

    /**
     * @param address where the server reaches the master
     * @param version the engine version of the master
     * @param protocol the protocol version of the master
     * @param startedAt when the master started
     */
    public record Master(String address, String version, String protocol, Instant startedAt) {}

    /**
     * A worker.
     *
     * @param resourceGroup the group of workers it belongs to
     * @param taskHeapBytes the memory for tasks
     * @param tasksRunning the tasks running on it now
     * @param sinceHeartbeatMs the time since its last heartbeat
     * @param draining whether it takes no new tasks ({@code pipeforge cluster drain})
     */
    public record Worker(
            String id,
            String version,
            String rpcAddress,
            String dataAddress,
            Map<String, String> labels,
            String resourceGroup,
            int cpuCores,
            long taskHeapBytes,
            long networkBytes,
            long managedBytes,
            List<Slot> slots,
            int tasksRunning,
            long sinceHeartbeatMs,
            boolean draining) {

        public Worker {
            labels = Map.copyOf(labels);
            slots = List.copyOf(slots);
        }

        public int slotsUsed() {
            return (int) slots.stream().filter(Slot::allocated).count();
        }
    }

    /**
     * A slot of a worker.
     *
     * @param job the job whose run holds it, or {@code null} when it is free
     */
    public record Slot(int index, boolean allocated, @Nullable String job) {}
}
