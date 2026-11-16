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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobId;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.InProcessRpc;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.SlotRequest;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.devlive.pipeforge.engine.worker.SlotTable;
import org.devlive.pipeforge.engine.worker.TaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Job masters running jobs on three workers in one process: slots reserved by the scheduler, tasks
 * deployed into them, failures, cancellation, a lost worker and too few slots.
 */
class ClusterJobTest {

    /** A worker: its task executor with two slots, reached through the in-process RPC. */
    static final class Worker implements TaskExecutorGateway {
        final String address;
        final SlotTable slots;
        final TaskExecutor executor;
        final InProcessRpc.Endpoint<TaskExecutorGateway> endpoint;
        final AtomicInteger deployed = new AtomicInteger();
        String id = "";

        Worker(String address, JobMasterGateway master) {
            this.address = address;
            this.slots = new SlotTable(address, 2);
            this.executor = new TaskExecutor(address, master, slots);
            this.endpoint = InProcessRpc.serve(address, executor);
        }

        @Override
        public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
            deployed.incrementAndGet();
            return endpoint.gateway().deployTask(deployment);
        }

        @Override
        public CompletableFuture<Void> cancelTask(ExecutionAttemptId task) {
            return endpoint.gateway().cancelTask(task);
        }

        @Override
        public CompletableFuture<Void> sendOperatorEvent(ExecutionAttemptId task, OperatorEvent event) {
            return endpoint.gateway().sendOperatorEvent(task, event);
        }

        @Override
        public CompletableFuture<Void> requestSlot(SlotRequest request) {
            return endpoint.gateway().requestSlot(request);
        }

        @Override
        public CompletableFuture<Void> freeSlot(String allocationId) {
            return endpoint.gateway().freeSlot(allocationId);
        }

        boolean idle() {
            return slots.snapshot().stream().noneMatch(SlotTable.Slot::allocated);
        }
    }

    final AtomicLong now = new AtomicLong();
    final ResourceManager manager = new ResourceManager(ResourceManager.Settings.DEFAULTS, now::get);
    final JobMasters jobs = new JobMasters();
    final InProcessRpc.Endpoint<JobMasterGateway> master = InProcessRpc.serve("master", jobs);
    final Map<String, Worker> workers = new ConcurrentHashMap<>();
    final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();
    final Scheduler scheduler =
            new Scheduler(manager, slot -> workers.get(slot.workerId()), thread, Duration.ofMillis(10));

    @BeforeEach
    void start() {
        manager.addListener(jobs);
        for (int i = 1; i <= 3; i++) {
            addWorker("worker-" + i + ":6124");
        }
    }

    Worker addWorker(String address) {
        return addWorker(address, master.gateway());
    }

    Worker addWorker(String address, JobMasterGateway reports) {
        return addWorker(address, reports, Map.of());
    }

    Worker addWorker(String address, JobMasterGateway reports, Map<String, String> labels) {
        Worker worker = new Worker(address, reports);
        worker.id = manager.register(new ResourceManager.WorkerSpec(
                        "0.1.0",
                        address,
                        address.replace("6124", "6125"),
                        new ResourceManager.Resources(2, 1L << 30, 0, 0),
                        2,
                        labels,
                        "default",
                        new byte[32],
                        null))
                .workerId();
        workers.put(worker.id, worker);
        return worker;
    }

    @AfterEach
    void stop() {
        workers.values().forEach(w -> {
            w.executor.close();
            w.endpoint.close();
        });
        master.close();
        thread.shutdownNow();
    }

    JobHandle submit(JobPlan plan, Duration slotTimeout) {
        return start(plan, slotTimeout, SubmitOptions.defaults()).handle();
    }

    JobMaster start(JobPlan plan, Duration slotTimeout, SubmitOptions options) {
        JobId id = JobId.random();
        JobMaster job = new JobMaster(
                id,
                plan,
                ChainTopology.graph(plan),
                options,
                Placement.cluster(
                        scheduler,
                        slot -> workers.get(slot.workerId()),
                        Map.of(),
                        Scheduler.Strategy.SPREAD,
                        slotTimeout),
                () -> jobs.remove(id.value()));
        jobs.add(job);
        job.start();
        return job;
    }

    static JobResult end(JobHandle handle) throws Exception {
        return handle.result().get(30, TimeUnit.SECONDS);
    }

    void awaitSlotsBack() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!(workers.values().stream().allMatch(Worker::idle)
                && manager.freeSlots(Map.of()).size() == 6)) {
            assertThat(System.nanoTime()).as("slots given back").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void runsAJobOnEveryWorkerAndGivesTheSlotsBack() throws Exception {
        String probe = Probe.create();
        JobResult result =
                end(submit(TestConnectors.job(probe, "rows: 300, splits: 6", "", "", 3), Duration.ofSeconds(5)));

        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertThat(result.metrics().rowsWritten()).isEqualTo(300);
        assertThat(result.metrics().reconciled()).isTrue();
        assertThat(Probe.get(probe).rows).hasSize(300);
        assertThat(workers.values())
                .allSatisfy(w -> assertThat(w.deployed).as(w.address).hasValue(1));
        awaitSlotsBack();
    }

    @Test
    void theFirstFailedTaskFailsTheJobAndCancelsTheOthers() throws Exception {
        String probe = Probe.create();
        JobResult result = end(submit(
                TestConnectors.job(probe, "rows: 100000, splits: 6", "fail-at: 50", "", 3), Duration.ofSeconds(5)));

        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code().id()).isEqualTo("PF-PLUG-0007");
        assertThat(result.error().scope().node()).isEqualTo("up");
        awaitSlotsBack();
    }

    @Test
    void cancelsTheTasksOnEveryWorker() throws Exception {
        String probe = Probe.create();
        JobHandle handle = submit(TestConnectors.job(probe, "block: cancel", null, "", 3), Duration.ofSeconds(5));
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();

        handle.cancel();
        JobResult result = end(handle);
        assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(result.error()).isNull();
        awaitSlotsBack();
    }

    @Test
    void aLostWorkerFailsTheJobWithTheWorkerAndTheTask() throws Exception {
        String probe = Probe.create();
        JobHandle handle = submit(TestConnectors.job(probe, "block: cancel", null, "", 3), Duration.ofSeconds(5));
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        while (handle.status() != JobStatus.RUNNING) {
            Thread.sleep(5);
        }

        // worker-2 falls silent, the others keep beating
        Worker silent = workers.values().stream()
                .filter(w -> w.address.startsWith("worker-2"))
                .findFirst()
                .orElseThrow();
        now.addAndGet(ResourceManager.Settings.DEFAULTS.heartbeatTimeout().toNanos());
        workers.values().stream().filter(w -> w != silent).forEach(w -> manager.heartbeat(w.id, List.of(), List.of()));
        assertThat(manager.expire())
                .extracting(ResourceManager.WorkerLost::workerId)
                .containsExactly(silent.id);

        JobResult result = end(handle);
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code()).isEqualTo(ErrorCode.WORKER_LOST);
        assertThat(result.error().params())
                .containsEntry("worker", silent.id)
                .containsEntry("address", "worker-2:6124");
        assertThat(result.error().scope().node()).isEqualTo("src");
        assertThat(result.error().scope().subtask()).isNotNull();
        assertThat(result.error().scope().run()).isEqualTo(result.id().value());
        assertThat(result.error().getMessage()).contains("worker-2:6124").contains("sent no heartbeat for 30 s");
    }

    @Test
    void theMetricsOfTheTasksOfALostWorkerStopCounting() throws Exception {
        String probe = Probe.create();
        JobMaster job = start(
                TestConnectors.job(probe, "block: cancel", null, "", 3),
                Duration.ofSeconds(5),
                SubmitOptions.defaults());
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        while (job.handle().status() != JobStatus.RUNNING || job.tasks().size() < 3) {
            Thread.sleep(5);
        }
        Worker silent = workers.values().stream()
                .filter(w -> w.address.startsWith("worker-2"))
                .findFirst()
                .orElseThrow();
        ExecutionAttemptId lost = job.tasks().stream()
                .filter(t -> t.workerId().equals(silent.id))
                .findFirst()
                .orElseThrow()
                .attempt();
        now.addAndGet(ResourceManager.Settings.DEFAULTS.heartbeatTimeout().toNanos());
        workers.values().stream().filter(w -> w != silent).forEach(w -> manager.heartbeat(w.id, List.of(), List.of()));
        assertThat(manager.expire()).hasSize(1);
        // a report the lost task sent before it was cut off arrives late: it no longer counts
        job.onMetrics(
                lost,
                new org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot(
                        List.of(
                                new org.devlive.pipeforge.engine.rpc.OperatorSnapshot("src", 0, 1_000_000, 0, 0, 0),
                                new org.devlive.pipeforge.engine.rpc.OperatorSnapshot(
                                        "out", 1_000_000, 1_000_000, 0, 0, 0)),
                        1_000_000_000L,
                        0,
                        0,
                        0));

        JobResult result = end(job.handle());
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.metrics().rowsRead()).isLessThan(1_000_000);
        assertThat(result.metrics().reconciled()).isTrue();
        assertThat(job.handle().metrics()).as("frozen once the run ended").isEqualTo(result.metrics());
    }

    @Test
    void failsWhenTheClusterHasTooFewSlots() throws Exception {
        JobResult result = end(submit(TestConnectors.job(Probe.create(), "", null, "", 7), Duration.ofMillis(200)));

        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code()).isEqualTo(ErrorCode.SLOTS_INSUFFICIENT);
        assertThat(result.error().params()).containsEntry("required", 7).containsEntry("available", 6);
        assertThat(workers.values()).allSatisfy(w -> assertThat(w.deployed).hasValue(0));
        awaitSlotsBack();
    }

    @Test
    void slotsThatArriveAfterACancelGoBack() throws Exception {
        JobHandle handle = submit(TestConnectors.job(Probe.create(), "", null, "", 7), Duration.ofSeconds(10));
        Thread.sleep(50);
        handle.cancel();
        assertThat(end(handle).status()).isEqualTo(JobStatus.CANCELLED);

        // a fourth worker makes the waiting request succeed; the job has ended, so the slots go back
        addWorker("worker-4:6124");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (manager.freeSlots(Map.of()).size() != 8 || scheduler.waiting().get() != 0) {
            assertThat(System.nanoTime()).as("slots back").isLessThan(deadline);
            Thread.sleep(10);
        }
        assertThat(workers.values()).allSatisfy(w -> {
            assertThat(w.deployed).hasValue(0);
            assertThat(w.idle()).isTrue();
        });
    }

    /** Delays every RUNNING update, as when it travels on another call than the split requests. */
    static JobMasterGateway runningLate(JobMasterGateway target, ScheduledExecutorService timer) {
        return new JobMasterGateway() {
            @Override
            public CompletableFuture<Void> updateTaskState(org.devlive.pipeforge.engine.rpc.TaskStateUpdate update) {
                if (update.state() != org.devlive.pipeforge.engine.rpc.TaskState.RUNNING) {
                    return target.updateTaskState(update);
                }
                CompletableFuture<Void> sent = new CompletableFuture<>();
                var unused = timer.schedule(
                        () -> target.updateTaskState(update).whenComplete((v, e) -> sent.complete(null)),
                        200,
                        TimeUnit.MILLISECONDS);
                return sent;
            }

            @Override
            public CompletableFuture<Void> sendReaderMessage(org.devlive.pipeforge.engine.rpc.ReaderMessage message) {
                return target.sendReaderMessage(message);
            }

            @Override
            public CompletableFuture<Void> reportMetrics(
                    ExecutionAttemptId task, org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot metrics) {
                return target.reportMetrics(task, metrics);
            }
        };
    }

    @Test
    void aSplitRequestThatArrivesBeforeTheReaderRunsIsServedOnceItDoes() throws Exception {
        JobMasterGateway late = runningLate(master.gateway(), thread);
        for (int i = 1; i <= 3; i++) {
            addWorker("late-" + i + ":6124", late, Map.of("reports", "late"));
        }
        String probe = Probe.create();
        JobPlan plan = TestConnectors.job(probe, "rows: 300, splits: 9", "", "", 3);
        JobId id = JobId.random();
        JobMaster job = new JobMaster(
                id,
                plan,
                ChainTopology.graph(plan),
                SubmitOptions.defaults(),
                Placement.cluster(
                        scheduler,
                        slot -> workers.get(slot.workerId()),
                        Map.of("reports", "late"),
                        Scheduler.Strategy.SPREAD,
                        Duration.ofSeconds(5)),
                () -> jobs.remove(id.value()));
        jobs.add(job);
        job.start();
        assertThat(end(job.handle()).status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(Probe.get(probe).rows).hasSize(300);
        assertThat(job.splitOwners().values().stream()
                        .mapToInt(java.util.Set::size)
                        .sum())
                .isEqualTo(9);
    }

    @Test
    void readersPullTheirSplitsAndEveryOwnerIsRecorded() throws Exception {
        String probe = Probe.create();
        JobMaster job = start(
                TestConnectors.job(probe, "rows: 3000, splits: 30", "", "", 3),
                Duration.ofSeconds(5),
                SubmitOptions.defaults());
        assertThat(end(job.handle()).status()).isEqualTo(JobStatus.SUCCESS);

        Map<Integer, java.util.Set<String>> owners = job.splitOwners();
        assertThat(owners).containsOnlyKeys(0, 1, 2);
        List<String> all =
                owners.values().stream().flatMap(java.util.Set::stream).toList();
        assertThat(all).hasSize(30).doesNotHaveDuplicates();
        // every reader asked for splits and was given some
        assertThat(owners.values()).allSatisfy(splits -> assertThat(splits).isNotEmpty());
        assertThat(Probe.get(probe).splitsPerReader).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(Probe.get(probe).rows).hasSize(3000);
    }

    @Test
    void aBlockedEnumeratorFailsTheRunInTime() throws Exception {
        String probe = Probe.create();
        long started = System.nanoTime();
        JobMaster job = start(
                TestConnectors.job(probe, "block-enumerator: start", null, "", 2),
                Duration.ofSeconds(5),
                SubmitOptions.defaults().withEnumeratorTimeout(Duration.ofMillis(300)));
        JobResult result = end(job.handle());

        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code()).isEqualTo(ErrorCode.ENUMERATOR_TIMEOUT);
        assertThat(result.error().params())
                .containsEntry("node", "src")
                .containsEntry("operation", "start the enumerator")
                .containsEntry("timeoutMs", 300L);
        assertThat(result.error().scope().node()).isEqualTo("src");
        // the run ends soon after the timeout: the blocked enumerator is not waited for again
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Probe.get(probe).events.contains("enumerator interrupted")) {
            assertThat(System.nanoTime()).as("the blocked call is interrupted").isLessThan(deadline);
            Thread.sleep(10);
        }
        assertThat(workers.values()).allSatisfy(w -> assertThat(w.deployed).hasValue(0));
        awaitSlotsBack();
    }
}
