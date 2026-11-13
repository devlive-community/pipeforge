/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.engine.master.ResourceManager.FreeSlot;
import org.devlive.pipeforge.engine.master.ResourceManager.RunningTask;
import org.devlive.pipeforge.engine.master.ResourceManager.Slot;
import org.devlive.pipeforge.engine.master.ResourceManager.WorkerLost;
import org.devlive.pipeforge.engine.master.ResourceManager.WorkerSpec;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.junit.jupiter.api.Test;

class ResourceManagerTest {

    final AtomicLong now = new AtomicLong();
    final ResourceManager manager = new ResourceManager(ResourceManager.Settings.DEFAULTS, now::get);

    static WorkerSpec spec(String address, int slots, Map<String, String> labels, String previous) {
        return new WorkerSpec(
                "0.1.0",
                address,
                address.replace("6124", "6125"),
                new ResourceManager.Resources(8, 4L << 30, 256L << 20, 0),
                slots,
                labels,
                "default",
                new byte[32],
                previous);
    }

    void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }

    @Test
    void registersWorkersWithFreeSlots() {
        var registration = manager.register(spec("a:6124", 2, Map.of("zone", "east"), null));
        assertThat(registration.leaderEpoch()).isEqualTo(1);
        assertThat(registration.heartbeatInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(registration.heartbeatTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(registration.workerId()).hasSize(36);

        assertThat(manager.workers()).singleElement().satisfies(w -> {
            assertThat(w.id()).isEqualTo(registration.workerId());
            assertThat(w.slots()).containsExactly(Slot.free(0), Slot.free(1));
            assertThat(w.freeSlots()).isEqualTo(2);
            assertThat(w.spec().labels()).containsEntry("zone", "east");
        });
    }

    @Test
    void aWorkerThatRegistersAgainKeepsItsIdAndStartsFree() {
        String id = manager.register(spec("a:6124", 2, Map.of(), null)).workerId();
        manager.offerSlots(id, List.of(new Slot(0, true, "job", "alloc-1")));
        assertThat(manager.register(spec("a:6124", 3, Map.of(), id)).workerId()).isEqualTo(id);
        assertThat(manager.workers()).singleElement().satisfies(w -> {
            assertThat(w.slots()).hasSize(3).allMatch(s -> !s.allocated());
        });
        // after a restart of the master, the worker brings its ID along
        assertThat(manager.register(spec("b:6124", 1, Map.of(), "known-before")).workerId())
                .isEqualTo("known-before");
    }

    @Test
    void heartbeatsCarrySlotsAndTasks() {
        String id = manager.register(spec("a:6124", 2, Map.of(), null)).workerId();
        ExecutionAttemptId task = new ExecutionAttemptId("run", "src", 0, 0);
        advance(Duration.ofSeconds(4));

        var ack = manager.heartbeat(
                id,
                List.of(new Slot(1, true, "job", "alloc"), new Slot(7, true, "job", "beyond the registered slots")),
                List.of(new RunningTask(task, TaskState.RUNNING)));
        assertThat(ack.reregister()).isFalse();
        assertThat(ack.leaderEpoch()).isEqualTo(1);
        assertThat(manager.workers()).singleElement().satisfies(w -> {
            assertThat(w.slots()).containsExactly(Slot.free(0), new Slot(1, true, "job", "alloc"));
            assertThat(w.tasks()).containsExactly(new RunningTask(task, TaskState.RUNNING));
            assertThat(w.sinceHeartbeat()).isZero();
        });

        // an empty slot list leaves the ledger as it is
        manager.heartbeat(id, List.of(), List.of());
        assertThat(manager.workers().get(0).freeSlots()).isEqualTo(1);
    }

    @Test
    void anUnknownWorkerIsAskedToRegister() {
        assertThat(manager.heartbeat("nobody", List.of(), List.of()).reregister())
                .isTrue();
        assertThat(manager.offerSlots("nobody", List.of(Slot.free(0)))).isFalse();
        assertThat(manager.workers()).isEmpty();
    }

    @Test
    void aSilentWorkerIsLostAfterTheTimeout() {
        List<WorkerLost> heard = new CopyOnWriteArrayList<>();
        manager.addListener(lost -> {
            throw new IllegalStateException("a broken listener does not stop the others");
        });
        manager.addListener(heard::add);
        String quiet = manager.register(spec("a:6124", 1, Map.of(), null)).workerId();
        String busy = manager.register(spec("b:6124", 1, Map.of(), null)).workerId();
        ExecutionAttemptId task = new ExecutionAttemptId("run", "src", 0, 0);
        manager.heartbeat(quiet, List.of(), List.of(new RunningTask(task, TaskState.RUNNING)));

        advance(Duration.ofSeconds(29));
        manager.heartbeat(busy, List.of(), List.of());
        assertThat(manager.expire()).isEmpty();

        advance(Duration.ofSeconds(1));
        List<WorkerLost> lost = manager.expire();
        assertThat(lost).singleElement().satisfies(w -> {
            assertThat(w.workerId()).isEqualTo(quiet);
            assertThat(w.rpcAddress()).isEqualTo("a:6124");
            assertThat(w.tasks()).containsExactly(new RunningTask(task, TaskState.RUNNING));
            assertThat(w.error().code()).isEqualTo(ErrorCode.WORKER_LOST);
            assertThat(w.error().params()).containsEntry("seconds", 30L).containsEntry("address", "a:6124");
            assertThat(w.error().getMessage()).contains("Worker " + quiet + " (a:6124) sent no heartbeat for 30 s");
        });
        assertThat(heard).isEqualTo(lost);
        assertThat(manager.workers()).extracting(ResourceManager.Worker::id).containsExactly(busy);
        assertThat(manager.heartbeat(quiet, List.of(), List.of()).reregister()).isTrue();
    }

    @Test
    void freeSlotsMatchLabels() {
        String east = manager.register(spec("a:6124", 2, Map.of("zone", "east", "net", "db"), null))
                .workerId();
        String west = manager.register(spec("b:6124", 1, Map.of("zone", "west"), null))
                .workerId();
        manager.offerSlots(east, List.of(new Slot(0, true, "job", "alloc")));

        assertThat(manager.freeSlots(Map.of()))
                .containsExactly(new FreeSlot(east, "a:6124", 1), new FreeSlot(west, "b:6124", 0));
        assertThat(manager.freeSlots(Map.of("zone", "east"))).containsExactly(new FreeSlot(east, "a:6124", 1));
        assertThat(manager.freeSlots(Map.of("zone", "east", "net", "db"))).hasSize(1);
        assertThat(manager.freeSlots(Map.of("zone", "east", "net", "web"))).isEmpty();
    }

    @Test
    void checksRunOnAScheduler() throws Exception {
        ResourceManager fast =
                new ResourceManager(new ResourceManager.Settings(Duration.ofMillis(10), Duration.ofMillis(40), 1));
        List<WorkerLost> heard = new ArrayList<>();
        fast.addListener(lost -> {
            synchronized (heard) {
                heard.add(lost);
                heard.notifyAll();
            }
        });
        fast.register(spec("a:6124", 1, Map.of(), null));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var checks = fast.scheduleChecks(scheduler);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (heard) {
                while (heard.isEmpty() && System.nanoTime() < deadline) {
                    heard.wait(50);
                }
            }
            assertThat(heard).hasSize(1);
            checks.cancel(false);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void rejectsSettingsAndSpecsThatCannotWork() {
        assertThatThrownBy(() -> new ResourceManager.Settings(Duration.ofSeconds(5), Duration.ofSeconds(5), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceManager.Settings(Duration.ZERO, Duration.ofSeconds(5), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec("a:6124", 0, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one slot");
        byte[] key = new byte[32];
        WorkerSpec spec = new WorkerSpec(
                "0.1.0", "a:1", "a:2", new ResourceManager.Resources(1, 1, 1, 1), 1, Map.of(), "", key, null);
        key[0] = 9;
        assertThat(spec.publicKey()[0]).as("copied in").isZero();
    }
}
