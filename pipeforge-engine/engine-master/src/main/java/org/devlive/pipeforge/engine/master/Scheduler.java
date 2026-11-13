/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.master.ResourceManager.FreeSlot;
import org.devlive.pipeforge.engine.rpc.SlotRequest;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reserves the slots of a job, all at once (native engine design, section 5): it picks free slots
 * of workers that carry the required labels, records them in the {@link ResourceManager} and asks
 * every worker to reserve its slot. A job that does not find enough slots waits in a queue and is
 * tried again as slots become free; after the timeout it fails with {@link
 * ErrorCode#SLOTS_INSUFFICIENT}. A slot that a worker refuses (another job took it first) is given
 * back and the whole request is tried again.
 *
 * <p>All scheduling runs on the one thread of the given executor.
 */
public final class Scheduler {

    private static final Logger LOG = LoggerFactory.getLogger(Scheduler.class);

    /** Where the slots of a job go. */
    public enum Strategy {
        /** One slot per worker in turn: the instances of a vertex are spread over the workers. */
        SPREAD,
        /** As many slots as possible on one worker before the next: less network traffic. */
        PACK
    }

    /**
     * What a job asks for.
     *
     * @param slots how many slots: the largest parallelism of the job
     * @param labels labels every worker must carry, e.g. {@code zone=east}
     * @param timeout how long the job may wait for slots ({@code scheduler.slot-request-timeout})
     */
    public record Requirements(int slots, Map<String, String> labels, Strategy strategy, Duration timeout) {

        /** The default of {@code scheduler.slot-request-timeout}. */
        public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

        /** {@code slots} anywhere, spread over the workers, waiting up to five minutes. */
        public static Requirements of(int slots) {
            return new Requirements(slots, Map.of(), Strategy.SPREAD, DEFAULT_TIMEOUT);
        }

        public Requirements {
            if (slots < 1) {
                throw new IllegalArgumentException("A job needs at least one slot, not " + slots);
            }
            labels = Map.copyOf(labels);
            Objects.requireNonNull(strategy, "strategy");
        }
    }

    /**
     * The slot of a job: slot {@code index} of the job runs on {@code worker}.
     *
     * @param index the job's own slot number; subtask i of every vertex runs in slot i
     */
    public record Assignment(int index, FreeSlot worker, String allocationId) {}

    /** The reserved slots of a job, by index. */
    public record Allocation(String jobId, String runId, List<Assignment> slots) {
        public Allocation {
            slots = List.copyOf(slots);
        }
    }

    /** The task executors of the workers, by worker. */
    @FunctionalInterface
    public interface Workers {
        TaskExecutorGateway gateway(FreeSlot slot);
    }

    private static final class Request {
        final String jobId;
        final String runId;
        final Requirements requirements;
        final long deadline;
        final CompletableFuture<Allocation> result = new CompletableFuture<>();
        boolean inFlight;

        Request(String jobId, String runId, Requirements requirements, long deadline) {
            this.jobId = jobId;
            this.runId = runId;
            this.requirements = requirements;
            this.deadline = deadline;
        }
    }

    private final ResourceManager manager;
    private final Workers workers;
    private final ScheduledExecutorService thread;
    private final LongSupplier nanoTime;
    private final List<Request> queue = new ArrayList<>();

    /**
     * @param thread runs every scheduling step; one thread
     * @param retryInterval how often waiting jobs are tried again
     */
    public Scheduler(
            ResourceManager manager, Workers workers, ScheduledExecutorService thread, Duration retryInterval) {
        this(manager, workers, thread, retryInterval, System::nanoTime);
    }

    @SuppressWarnings("FutureReturnValueIgnored") // runs until the executor stops
    Scheduler(
            ResourceManager manager,
            Workers workers,
            ScheduledExecutorService thread,
            Duration retryInterval,
            LongSupplier nanoTime) {
        this.manager = manager;
        this.workers = workers;
        this.thread = thread;
        this.nanoTime = nanoTime;
        long interval = Math.max(1, retryInterval.toMillis());
        thread.scheduleWithFixedDelay(this::step, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Reserves the slots of a run; completes when every worker has reserved its slot. */
    public CompletableFuture<Allocation> allocate(String jobId, String runId, Requirements requirements) {
        Request request = new Request(
                jobId,
                runId,
                requirements,
                nanoTime.getAsLong() + requirements.timeout().toNanos());
        thread.execute(() -> {
            queue.add(request);
            step();
        });
        return request.result;
    }

    /** Gives the slots of an allocation back, on the workers and in the ledger. */
    public void release(Allocation allocation) {
        thread.execute(() -> giveBack(allocation.slots()));
    }

    /** The number of jobs waiting for slots. */
    public CompletableFuture<Integer> waiting() {
        return CompletableFuture.supplyAsync(queue::size, thread);
    }

    /** Tries every waiting job in the order they came, and fails those past their timeout. */
    private void step() {
        long now = nanoTime.getAsLong();
        for (Request request : List.copyOf(queue)) {
            if (request.inFlight) {
                continue;
            }
            List<FreeSlot> free = manager.freeSlots(request.requirements.labels());
            if (now - request.deadline >= 0) {
                queue.remove(request);
                request.result.completeExceptionally(PipeForgeException.of(ErrorCode.SLOTS_INSUFFICIENT)
                        .param("job", request.jobId)
                        .param("required", request.requirements.slots())
                        .param("available", free.size())
                        .param("timeout", text(request.requirements.timeout()))
                        .build());
                continue;
            }
            if (free.size() >= request.requirements.slots()) {
                attempt(request, pick(free, request.requirements));
            }
        }
    }

    /** A duration as people read it: {@code 300 s}, or {@code 100 ms} below a second. */
    static String text(Duration duration) {
        return duration.toMillis() < 1000 ? duration.toMillis() + " ms" : duration.toSeconds() + " s";
    }

    /** {@code requirements.slots()} of the free slots, by the strategy. */
    static List<FreeSlot> pick(List<FreeSlot> free, Requirements requirements) {
        Map<String, List<FreeSlot>> byWorker = new LinkedHashMap<>();
        free.forEach(s ->
                byWorker.computeIfAbsent(s.workerId(), w -> new ArrayList<>()).add(s));
        List<List<FreeSlot>> workers = new ArrayList<>(byWorker.values());
        // workers with the most free slots first; the order of registration among equals
        workers.sort(Comparator.comparingInt(List<FreeSlot>::size).reversed());
        List<FreeSlot> picked = new ArrayList<>();
        int wanted = requirements.slots();
        if (requirements.strategy() == Strategy.PACK) {
            for (List<FreeSlot> slots : workers) {
                for (FreeSlot slot : slots) {
                    if (picked.size() < wanted) {
                        picked.add(slot);
                    }
                }
            }
        } else {
            for (int round = 0; picked.size() < wanted; round++) {
                for (List<FreeSlot> slots : workers) {
                    if (round < slots.size() && picked.size() < wanted) {
                        picked.add(slots.get(round));
                    }
                }
            }
        }
        return picked;
    }

    @SuppressWarnings("FutureReturnValueIgnored") // the callbacks post back to the scheduling thread
    private void attempt(Request request, List<FreeSlot> slots) {
        List<Assignment> reserved = new ArrayList<>();
        for (FreeSlot slot : slots) {
            String allocationId = UUID.randomUUID().toString();
            if (!manager.reserve(slot, request.jobId, allocationId)) {
                giveBack(reserved);
                return;
            }
            reserved.add(new Assignment(reserved.size(), slot, allocationId));
        }
        request.inFlight = true;
        List<CompletableFuture<Void>> calls = new ArrayList<>();
        for (Assignment assignment : reserved) {
            calls.add(workers.gateway(assignment.worker())
                    .requestSlot(new SlotRequest(
                            request.jobId,
                            request.runId,
                            assignment.allocationId(),
                            assignment.worker().index())));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new))
                .whenCompleteAsync(
                        (ignored, error) -> {
                            request.inFlight = false;
                            if (error == null) {
                                queue.remove(request);
                                request.result.complete(new Allocation(request.jobId, request.runId, reserved));
                            } else {
                                LOG.info(
                                        "A worker refused a slot of job {}; trying again: {}",
                                        request.jobId,
                                        error.getMessage());
                                giveBack(reserved);
                            }
                        },
                        thread);
    }

    @SuppressWarnings("FutureReturnValueIgnored") // a worker releases unused slots by itself
    private void giveBack(List<Assignment> assignments) {
        for (Assignment assignment : assignments) {
            manager.release(assignment.worker().workerId(), assignment.worker().index(), assignment.allocationId());
            workers.gateway(assignment.worker()).freeSlot(assignment.allocationId());
        }
    }
}
