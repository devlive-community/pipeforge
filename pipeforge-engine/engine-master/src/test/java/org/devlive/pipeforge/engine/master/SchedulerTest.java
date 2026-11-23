/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.master.ResourceManager.FreeSlot;
import org.devlive.pipeforge.engine.master.Scheduler.Requirements;
import org.devlive.pipeforge.engine.master.Scheduler.Strategy;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.SlotRequest;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchedulerTest {

    /** A worker's task executor that records reservations and can refuse some. */
    static final class Worker implements TaskExecutorGateway {
        final List<SlotRequest> reserved = new CopyOnWriteArrayList<>();
        final List<String> freed = new CopyOnWriteArrayList<>();
        final AtomicInteger refusals = new AtomicInteger();

        @Override
        public CompletableFuture<Void> requestSlot(SlotRequest request) {
            if (refusals.getAndDecrement() > 0) {
                return CompletableFuture.failedFuture(PipeForgeException.of(ErrorCode.SLOT_TAKEN)
                        .param("worker", "w")
                        .param("slot", request.slot())
                        .param("holder", "another job")
                        .build());
            }
            reserved.add(request);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> freeSlot(String allocationId) {
            freed.add(allocationId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> cancelTask(ExecutionAttemptId task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> sendOperatorEvent(ExecutionAttemptId task, OperatorEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> triggerCheckpoint(org.devlive.pipeforge.engine.rpc.CheckpointTrigger trigger) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> notifyCheckpointComplete(ExecutionAttemptId task, long checkpointId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> abortCheckpoint(ExecutionAttemptId task, long checkpointId, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    final ResourceManager manager = new ResourceManager(ResourceManager.Settings.DEFAULTS);
    final Map<String, Worker> workers = new ConcurrentHashMap<>();
    final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();
    final Scheduler scheduler = new Scheduler(
            manager,
            slot -> workers.computeIfAbsent(slot.workerId(), w -> new Worker()),
            thread,
            Duration.ofMillis(10));

    @AfterEach
    void stop() {
        thread.shutdownNow();
    }

    String worker(String address, int slots, Map<String, String> labels) {
        return manager.register(ResourceManagerTest.spec(address, slots, labels, null))
                .workerId();
    }

    static Requirements needs(int slots, Strategy strategy) {
        return new Requirements(slots, Map.of(), strategy, Duration.ofSeconds(10));
    }

    static List<FreeSlot> free(int... perWorker) {
        List<FreeSlot> slots = new java.util.ArrayList<>();
        for (int w = 0; w < perWorker.length; w++) {
            for (int i = 0; i < perWorker[w]; i++) {
                slots.add(new FreeSlot("w" + w, "w" + w + ":6124", "w" + w + ":6125", i));
            }
        }
        return slots;
    }

    @ParameterizedTest(name = "{0} of {1} slot(s) per worker, {2}: {3}")
    @CsvSource(
            delimiter = '|',
            value = {
                "3 | 2 2 2 | SPREAD | w0 w1 w2",
                "4 | 2 2 2 | SPREAD | w0 w1 w2 w0",
                "3 | 2 2 2 | PACK   | w0 w0 w1",
                "3 | 1 3 2 | PACK   | w1 w1 w1",
                "4 | 1 3 2 | SPREAD | w1 w2 w0 w1",
                "1 | 1 1   | SPREAD | w0"
            })
    void placesSlotsByStrategy(int wanted, String perWorker, Strategy strategy, String expected) {
        int[] counts = java.util.Arrays.stream(perWorker.trim().split(" "))
                .mapToInt(Integer::parseInt)
                .toArray();
        List<FreeSlot> picked = Scheduler.pick(free(counts), needs(wanted, strategy));
        assertThat(picked)
                .extracting(FreeSlot::workerId)
                .containsExactly(expected.trim().split(" "));
        assertThat(picked).doesNotHaveDuplicates();
    }

    @Test
    void reservesEverySlotOnItsWorkerAndInTheLedger() throws Exception {
        String a = worker("a:6124", 2, Map.of());
        String b = worker("b:6124", 2, Map.of());

        Scheduler.Allocation allocation =
                scheduler.allocate("job-1", "run-1", needs(3, Strategy.SPREAD)).get(5, TimeUnit.SECONDS);

        assertThat(allocation.slots()).extracting(Scheduler.Assignment::index).containsExactly(0, 1, 2);
        assertThat(allocation.slots()).extracting(s -> s.worker().workerId()).containsExactly(a, b, a);
        assertThat(workers.get(a).reserved).extracting(SlotRequest::slot).containsExactly(0, 1);
        assertThat(workers.get(b).reserved).singleElement().satisfies(r -> {
            assertThat(r.jobId()).isEqualTo("job-1");
            assertThat(r.runId()).isEqualTo("run-1");
            assertThat(r.allocationId()).isEqualTo(allocation.slots().get(1).allocationId());
        });
        assertThat(manager.freeSlots(Map.of())).hasSize(1);
        assertThat(manager.workers().get(0).slots())
                .allSatisfy(s -> assertThat(s.jobId()).isEqualTo("job-1"));

        scheduler.release(allocation);
        assertThat(scheduler.waiting().get(5, TimeUnit.SECONDS)).isZero();
        assertThat(manager.freeSlots(Map.of())).hasSize(4);
        assertThat(workers.get(a).freed).hasSize(2);
    }

    @Test
    void requirementsHaveDefaults() {
        Requirements defaults = Requirements.of(3);
        assertThat(defaults.strategy()).isEqualTo(Strategy.SPREAD);
        assertThat(defaults.labels()).isEmpty();
        assertThat(defaults.timeout()).isEqualTo(Duration.ofMinutes(5));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Requirements.of(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Scheduler.text(Duration.ofMinutes(5))).isEqualTo("300 s");
    }

    @Test
    void usesOnlyWorkersWithTheLabels() throws Exception {
        worker("west:6124", 4, Map.of("zone", "west"));
        String east = worker("east:6124", 2, Map.of("zone", "east"));
        var allocation = scheduler
                .allocate(
                        "job",
                        "run",
                        new Requirements(2, Map.of("zone", "east"), Strategy.SPREAD, Duration.ofSeconds(5)))
                .get(5, TimeUnit.SECONDS);
        assertThat(allocation.slots())
                .allSatisfy(s -> assertThat(s.worker().workerId()).isEqualTo(east));
    }

    @Test
    void waitsUntilEnoughSlotsAreFree() throws Exception {
        worker("a:6124", 2, Map.of());
        CompletableFuture<Scheduler.Allocation> waiting = scheduler.allocate("job", "run", needs(4, Strategy.PACK));
        Thread.sleep(100);
        assertThat(waiting).isNotDone();
        assertThat(scheduler.waiting().get(5, TimeUnit.SECONDS)).isEqualTo(1);

        worker("b:6124", 2, Map.of());
        assertThat(waiting.get(5, TimeUnit.SECONDS).slots()).hasSize(4);
        assertThat(scheduler.waiting().get(5, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void failsWhenNoSlotsBecomeFreeInTime() {
        worker("a:6124", 2, Map.of());
        CompletableFuture<Scheduler.Allocation> request = scheduler.allocate(
                "orders-sync", "run", new Requirements(5, Map.of(), Strategy.SPREAD, Duration.ofMillis(100)));
        assertThat(request)
                .failsWithin(5, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SLOTS_INSUFFICIENT);
                    assertThat(e.params())
                            .containsEntry("job", "orders-sync")
                            .containsEntry("required", 5)
                            .containsEntry("available", 2);
                    assertThat(e.getMessage()).contains("needs 5 slot(s) but found 2 free within 100 ms");
                });
        assertThat(manager.freeSlots(Map.of())).hasSize(2);
    }

    @Test
    void triesAgainWhenAWorkerRefusesASlot() throws Exception {
        String a = worker("a:6124", 2, Map.of());
        workers.computeIfAbsent(a, w -> new Worker()).refusals.set(1);

        var allocation =
                scheduler.allocate("job", "run", needs(2, Strategy.PACK)).get(5, TimeUnit.SECONDS);

        assertThat(allocation.slots()).hasSize(2);
        // the first attempt's reservations were given back on the worker
        assertThat(workers.get(a).freed).hasSize(2);
        assertThat(workers.get(a).reserved).hasSize(3);
    }
}
