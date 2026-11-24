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
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.devlive.pipeforge.engine.worker.SlotTable;
import org.devlive.pipeforge.engine.worker.TaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        /** A dead worker's process is gone: nothing it does reaches the master any more. */
        volatile boolean dead;

        String id = "";

        Worker(String address, JobMasterGateway master) {
            this.address = address;
            this.slots = new SlotTable(address, 2);
            JobMasterGateway reports = (JobMasterGateway) java.lang.reflect.Proxy.newProxyInstance(
                    JobMasterGateway.class.getClassLoader(),
                    new Class<?>[] {JobMasterGateway.class},
                    (p, method, args) -> {
                        if (dead && method.getReturnType() == CompletableFuture.class) {
                            return CompletableFuture.completedFuture(null);
                        }
                        try {
                            return method.invoke(master, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            // rows up to 4KB, so that a test can send larger ones cheaply
            this.executor = new TaskExecutor(
                    address,
                    reports,
                    slots,
                    null,
                    null,
                    TaskExecutor.Options.of(
                            new org.devlive.pipeforge.engine.worker.MemoryLimits(4096, Long.MAX_VALUE)));
            this.endpoint = InProcessRpc.serve(address, executor);
        }

        /** When a task was last deployed here, by {@link System#nanoTime()}. */
        volatile long lastDeployNanos;

        @Override
        public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
            deployed.incrementAndGet();
            lastDeployNanos = System.nanoTime();
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

        /** Answers triggers in place of the task executor, as barriers would; else the executor does. */
        volatile java.util.function.@org.jspecify.annotations.Nullable Consumer<
                        org.devlive.pipeforge.engine.rpc.CheckpointTrigger>
                barriers;

        @Override
        public CompletableFuture<Void> triggerCheckpoint(org.devlive.pipeforge.engine.rpc.CheckpointTrigger trigger) {
            var answer = barriers;
            if (answer != null) {
                answer.accept(trigger);
                return CompletableFuture.completedFuture(null);
            }
            return endpoint.gateway().triggerCheckpoint(trigger);
        }

        @Override
        public CompletableFuture<Void> notifyCheckpointComplete(ExecutionAttemptId task, long checkpointId) {
            return endpoint.gateway().notifyCheckpointComplete(task, checkpointId);
        }

        @Override
        public CompletableFuture<Void> abortCheckpoint(ExecutionAttemptId task, long checkpointId, String reason) {
            return endpoint.gateway().abortCheckpoint(task, checkpointId, reason);
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
        return start(plan, slotTimeout, options, null);
    }

    JobMaster start(
            JobPlan plan,
            Duration slotTimeout,
            SubmitOptions options,
            org.devlive.pipeforge.engine.master.checkpoint.@org.jspecify.annotations.Nullable Checkpoints checkpoints) {
        return start(plan, slotTimeout, options, checkpoints, null);
    }

    JobMaster start(
            JobPlan plan,
            Duration slotTimeout,
            SubmitOptions options,
            org.devlive.pipeforge.engine.master.checkpoint.@org.jspecify.annotations.Nullable Checkpoints checkpoints,
            org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.@org.jspecify.annotations.Nullable Settings
                    restoreFrom) {
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
                () -> jobs.remove(id.value()),
                checkpoints,
                restoreFrom);
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
    void aRowTooLargeFailsItsJobOnlyAndItsTasksHoldNothingAfterwards() throws Exception {
        String good = Probe.create();
        String bad = Probe.create();
        // both jobs share the three workers
        JobHandle large =
                submit(TestConnectors.job(bad, "rows: 30, splits: 3, pad: 10000", "", "", 3), Duration.ofSeconds(5));
        JobHandle normal =
                submit(TestConnectors.job(good, "rows: 3000, splits: 6, pad: 500", "", "", 3), Duration.ofSeconds(5));

        JobResult failed = end(large);
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.error().code()).isEqualTo(ErrorCode.ROW_TOO_LARGE);
        assertThat(failed.error().scope().node()).isEqualTo("src");
        assertThat(failed.error().getMessage()).contains("more than task.max-row-bytes (4096)");
        assertThat(Probe.get(bad).rows).isEmpty();

        JobResult succeeded = end(normal);
        assertThat(succeeded.status()).as(String.valueOf(succeeded.error())).isEqualTo(JobStatus.SUCCESS);
        assertThat(Probe.get(good).rows).hasSize(3000);
        assertThat(succeeded.metrics().memoryBytes())
                .as("the tasks hold nothing once they ended")
                .isZero();
        assertThat(workers.values())
                .allSatisfy(w -> assertThat(w.executor.accountedMemory()).isZero());
        awaitSlotsBack();
    }

    @Test
    void splitsCountAsReadWhenTheirReadersSayAndTheTotalComesFromTheEnumerator() throws Exception {
        String probe = Probe.create();
        JobMaster job = start(
                TestConnectors.job(probe, "rows: 600, splits: 6, pause-after-splits: 2", "", "", 1),
                Duration.ofSeconds(5),
                SubmitOptions.defaults());
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        // the reader runs on, but has read two splits: they count before the reader finishes
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (job.handle().metrics().splitsFinished() < 2) {
            assertThat(System.nanoTime()).as("two splits counted as read").isLessThan(deadline);
            Thread.sleep(20);
        }
        assertThat(job.handle().metrics().splitsFinished()).isEqualTo(2);
        assertThat(job.handle().metrics().splitsAssigned())
                .as("the enumerator's total, not the splits assigned so far")
                .isEqualTo(6);
        // the reader paused before asking for its third split: two assigned, both read
        assertThat(job.splits()).hasSize(2).allMatch(JobMaster.SplitView::finished);

        Probe.get(probe).release.countDown();
        JobResult result = end(job.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertThat(result.metrics().splitsFinished()).isEqualTo(6);
        assertThat(result.metrics().splitsAssigned()).isEqualTo(6);
        awaitSlotsBack();
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
    void failuresAreClassifiedAndTheRestartStrategyIsAsked() throws Exception {
        String transientProbe = Probe.create();
        JobMaster deadlocked = start(
                TestConnectors.job(transientProbe, "rows: 300, splits: 3", "", "fail-at: 5, fail-with: deadlock", 3),
                Duration.ofSeconds(5),
                SubmitOptions.defaults());
        JobResult first = end(deadlocked.handle());
        assertThat(first.status()).isEqualTo(JobStatus.FAILED);
        assertThat(first.error().kind()).isEqualTo(org.devlive.pipeforge.common.error.ErrorKind.TRANSIENT);
        assertThat(deadlocked.decision())
                .as("a run without checkpoints cannot restart its tasks")
                .isInstanceOfSatisfying(
                        org.devlive.pipeforge.engine.master.failure.FailureHandler.Fail.class,
                        fail -> assertThat(fail.exhausted()).isFalse());
        assertThat(deadlocked.restarts()).isZero();
        awaitSlotsBack();

        String fatalProbe = Probe.create();
        JobMaster denied = start(
                TestConnectors.job(fatalProbe, "rows: 300, splits: 3, fail-at: 5, fail-with: denied", "", "", 3),
                Duration.ofSeconds(5),
                SubmitOptions.defaults());
        JobResult second = end(denied.handle());
        assertThat(second.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(denied.decision())
                .isInstanceOfSatisfying(
                        org.devlive.pipeforge.engine.master.failure.FailureHandler.Fail.class,
                        fail -> assertThat(fail.exhausted()).isFalse());
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
                                new org.devlive.pipeforge.engine.rpc.OperatorSnapshot(
                                        "src", 1_000_000, 1_000_000, 0, 0, 0),
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

            @Override
            public CompletableFuture<Void> acknowledgeCheckpoint(org.devlive.pipeforge.engine.rpc.CheckpointAck ack) {
                return target.acknowledgeCheckpoint(ack);
            }

            @Override
            public CompletableFuture<Void> declineCheckpoint(
                    ExecutionAttemptId task,
                    long checkpointId,
                    org.devlive.pipeforge.common.error.PipeForgeException reason) {
                return target.declineCheckpoint(task, checkpointId, reason);
            }
        };
    }

    static JobPlan withCheckpoints(JobPlan plan, Duration interval, int tolerable, int retained) {
        return new JobPlan(
                plan.name(),
                plan.description(),
                plan.engine(),
                plan.mode(),
                plan.modeDerived(),
                plan.delivery(),
                plan.parallelism(),
                plan.timeZone(),
                plan.variables(),
                plan.nodes(),
                plan.warnings(),
                plan.dirty(),
                plan.limit(),
                new org.devlive.pipeforge.core.plan.CheckpointSettings(
                        true,
                        interval,
                        Duration.ofSeconds(10),
                        Duration.ZERO,
                        1,
                        tolerable,
                        retained,
                        org.devlive.pipeforge.core.plan.CheckpointSettings.Externalized.RETAIN_ON_CANCELLATION));
    }

    /** {@code plan} restarting {@code attempts} times, {@code delay} apart. */
    static JobPlan withRestarts(JobPlan plan, int attempts, Duration delay) {
        return new JobPlan(
                plan.name(),
                plan.description(),
                plan.engine(),
                plan.mode(),
                plan.modeDerived(),
                plan.delivery(),
                plan.parallelism(),
                plan.timeZone(),
                plan.variables(),
                plan.nodes(),
                plan.warnings(),
                plan.dirty(),
                plan.limit(),
                plan.checkpoint(),
                new org.devlive.pipeforge.core.plan.RestartSettings(
                        org.devlive.pipeforge.core.plan.RestartSettings.Strategy.FIXED_DELAY,
                        attempts,
                        delay,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(5),
                        2,
                        Duration.ofHours(1),
                        3,
                        Duration.ofMinutes(5)));
    }

    /** The ids the sink committed, each as often as it was committed. */
    static List<Long> committedIds(String probe) {
        synchronized (Probe.get(probe).rows) {
            return Probe.get(probe).rows.stream()
                    .map(r -> (Long) r.get(0))
                    .sorted()
                    .toList();
        }
    }

    /** Every id below {@code rows} was committed exactly once; the message lists what was not. */
    static void assertExactlyOnce(String probe, int rows) {
        Map<Long, Long> counts = new java.util.TreeMap<>();
        committedIds(probe).forEach(id -> counts.merge(id, 1L, Long::sum));
        List<Long> missing =
                ids(rows).stream().filter(id -> !counts.containsKey(id)).toList();
        Map<Long, Long> twice = new java.util.TreeMap<>(counts);
        twice.values().removeIf(c -> c == 1);
        assertThat(missing.size() + twice.size())
                .as(
                        "missing %d %s, more than once %d %s, events %s",
                        missing.size(),
                        ranges(missing),
                        twice.size(),
                        ranges(List.copyOf(twice.keySet())),
                        Probe.get(probe).events)
                .isZero();
    }

    private static String ranges(List<Long> ids) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ids.size(); ) {
            int j = i;
            while (j + 1 < ids.size() && ids.get(j + 1) == ids.get(j) + 1) {
                j++;
            }
            text.append(ids.get(i)).append(i == j ? "" : "-" + ids.get(j)).append(' ');
            i = j + 1;
        }
        return text.toString();
    }

    static List<Long> ids(int rows) {
        return java.util.stream.LongStream.range(0, rows).boxed().toList();
    }

    /** How long the tasks of the runs of these tests take to stop at most: half the fence wait. */
    static final Duration CANCEL_TIMEOUT = Duration.ofMillis(400);

    JobMaster startWithCheckpoints(JobPlan plan, java.nio.file.Path dir) {
        return start(
                withRestarts(withCheckpoints(plan, Duration.ofMillis(100), 3, 2), 3, Duration.ofMillis(50)),
                Duration.ofSeconds(5),
                // a short write queue, so that barriers do not wait behind most of the input
                SubmitOptions.defaults()
                        .withWriteBatchSize(10)
                        .withWriteQueueCapacity(20)
                        .withCancelTimeout(CANCEL_TIMEOUT),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                        new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir),
                        new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(),
                        Runnable::run));
    }

    @Test
    void onlyTheRegionOfAFailedTaskRestartsAndEveryRowIsCommittedOnce(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String probe = Probe.create();
        JobMaster job = startWithCheckpoints(
                TestConnectors.job(
                        probe,
                        "rows: 3000, splits: 12",
                        null,
                        "commit: transactional, slow-ms: 1, fail-at: 100, fail-subtask: 1, fail-times: 1,"
                                + " fail-with: deadlock",
                        3),
                dir);

        JobResult result = end(job.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertExactlyOnce(probe, 3000);
        assertThat(job.restarts()).isOne();
        assertThat(workers.values().stream().mapToInt(w -> w.deployed.get()).sum())
                .as("three tasks, and the one of the failed region again")
                .isEqualTo(4);
        awaitSlotsBack();
    }

    @Test
    void theTasksOfALostWorkerRestartElsewhereFromTheLastCheckpoint(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String probe = Probe.create();
        JobMaster job = startWithCheckpoints(
                TestConnectors.job(probe, "rows: 3000, splits: 12", null, "commit: transactional, slow-ms: 1", 3), dir);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (job.checkpointStats().lastCompletedId() < 2) {
            assertThat(System.nanoTime())
                    .as(
                            "two checkpoints completed: %s %s %s",
                            job.handle().status(), job.checkpointStats(), job.tasks())
                    .isLessThan(deadline);
            Thread.sleep(10);
        }

        // worker-2 stops: its tasks are gone, and it sends no more heartbeats
        Worker silent = workers.values().stream()
                .filter(w -> w.address.startsWith("worker-2"))
                .findFirst()
                .orElseThrow();
        silent.dead = true;
        silent.executor.close();
        now.addAndGet(ResourceManager.Settings.DEFAULTS.heartbeatTimeout().toNanos());
        workers.values().stream().filter(w -> w != silent).forEach(w -> manager.heartbeat(w.id, List.of(), List.of()));
        long lost = System.nanoTime();
        assertThat(manager.expire())
                .extracting(ResourceManager.WorkerLost::workerId)
                .containsExactly(silent.id);

        JobResult result = end(job.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertExactlyOnce(probe, 3000);
        assertThat(job.restarts()).isOne();
        assertThat(Probe.get(probe).restoredStates)
                .as("the restarted writer got its state from the checkpoint")
                .hasSize(1);
        assertThat(job.tasks()).as("no task runs on the lost worker any more").noneMatch(t -> t.workerId()
                .equals(silent.id));
        assertThat(workers.values().stream()
                                .mapToLong(w -> w.lastDeployNanos)
                                .max()
                                .orElseThrow()
                        - lost)
                .as("its tasks were deployed again only once the worker had surely stopped them")
                .isGreaterThanOrEqualTo(CANCEL_TIMEOUT.multipliedBy(2).toNanos());
    }

    @Test
    void aTaskItsWorkerStoppedRestartsItsRegionInsteadOfEndingQuietly(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String probe = Probe.create();
        JobMaster job = startWithCheckpoints(
                TestConnectors.job(probe, "rows: 3000, splits: 12", null, "commit: transactional, slow-ms: 1", 3), dir);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (job.checkpointStats().lastCompletedId() < 1) {
            assertThat(System.nanoTime()).as("a checkpoint completed").isLessThan(deadline);
            Thread.sleep(10);
        }
        // the worker cancels a task on its own, as when it shuts down
        JobMaster.TaskView victim = job.tasks().get(1);
        workers.get(victim.workerId()).executor.cancelTask(victim.attempt()).get();

        JobResult result = end(job.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertExactlyOnce(probe, 3000);
        assertThat(job.restarts()).isOne();
        awaitSlotsBack();
    }

    /** {@code plan} with or without a last checkpoint before it finishes. */
    static JobPlan onFinish(JobPlan plan, boolean value) {
        var c = plan.checkpoint();
        return new JobPlan(
                plan.name(),
                plan.description(),
                plan.engine(),
                plan.mode(),
                plan.modeDerived(),
                plan.delivery(),
                plan.parallelism(),
                plan.timeZone(),
                plan.variables(),
                plan.nodes(),
                plan.warnings(),
                plan.dirty(),
                plan.limit(),
                new org.devlive.pipeforge.core.plan.CheckpointSettings(
                        c.enabled(),
                        c.interval(),
                        c.timeout(),
                        c.minPause(),
                        c.maxConcurrent(),
                        c.tolerableFailures(),
                        c.retained(),
                        c.externalized(),
                        value),
                plan.restart());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBoundedRunRecordsALastCheckpointBeforeItCommitsWhatItPrepared(
            boolean last, @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String probe = Probe.create();
        var store = new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(Probe.get(probe).events);
        // checkpoints too rare to complete: everything is committed at the end
        JobMaster job = start(
                onFinish(
                        withCheckpoints(
                                TestConnectors.job(probe, "rows: 600, splits: 6", null, "commit: transactional", 3),
                                Duration.ofHours(1),
                                3,
                                2),
                        last),
                Duration.ofSeconds(5),
                SubmitOptions.defaults(),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                        new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir),
                        store,
                        Runnable::run));

        JobResult result = end(job.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertExactlyOnce(probe, 600);
        assertThat(Probe.get(probe).committed)
                .as("the final transaction of every sink task")
                .hasSize(3)
                .allSatisfy(t -> assertThat(t).endsWith("-" + Long.MAX_VALUE));
        List<String> events = List.copyOf(Probe.get(probe).events).stream()
                .filter(e -> e.matches("(begin|complete|delete|committed) .*"))
                .map(e -> e.startsWith("committed") ? "committed" : e)
                .distinct()
                .toList();
        if (last) {
            assertThat(events)
                    .as("recorded first, then committed; a run that succeeded keeps no checkpoint")
                    .containsExactly("begin 1 FINISH", "complete 1", "committed", "delete 1");
            assertThat(result.metrics().lastCompletedCheckpointId()).isEqualTo(1);
        } else {
            assertThat(events).containsExactly("committed");
        }
        awaitSlotsBack();
    }

    @Test
    void aSavepointDoesNotDisturbTheRunAndOutlivesIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        String probe = Probe.create();
        var storage = new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir);
        JobMaster job = start(
                withCheckpoints(
                        TestConnectors.job(
                                probe, "rows: 3000, splits: 12", null, "commit: transactional, slow-ms: 1", 3),
                        Duration.ofHours(1),
                        3,
                        2),
                Duration.ofSeconds(5),
                SubmitOptions.defaults().withWriteBatchSize(10).withWriteQueueCapacity(20),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                        storage,
                        new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(),
                        Runnable::run));
        while (job.handle().status() != JobStatus.RUNNING
                || job.tasks().stream().anyMatch(t -> t.state() != TaskState.RUNNING)) {
            Thread.sleep(5);
        }

        var savepoint = job.triggerSavepoint("before-upgrade").get(20, TimeUnit.SECONDS);
        assertThat(savepoint.type()).isEqualTo(org.devlive.pipeforge.engine.rpc.CheckpointType.SAVEPOINT);
        assertThat(savepoint.directory()).startsWith(job.runId() + "/savepoints/before-upgrade-");
        assertThat(job.triggerSavepoint("no/slashes"))
                .failsWithin(Duration.ofSeconds(5))
                .withThrowableThat()
                .havingRootCause()
                .isInstanceOfSatisfying(
                        org.devlive.pipeforge.common.error.PipeForgeException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REQUEST_INVALID));

        JobResult result = end(job.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertExactlyOnce(probe, 3000);
        assertThat(storage.isComplete(savepoint.directory()))
                .as("a run that succeeded deletes its checkpoints, not its savepoints")
                .isTrue();
        awaitSlotsBack();
    }

    @Test
    void aRunStartedFromASavepointGoesOnWhereItWasTaken(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        String probe = Probe.create();
        var storage = new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir);
        var store = new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore();
        // only the savepoint commits: no checkpoints in between
        JobPlan plan = withCheckpoints(
                TestConnectors.job(probe, "rows: 3000, splits: 12", null, "commit: transactional, slow-ms: 1", 3),
                Duration.ofHours(1),
                3,
                2);
        SubmitOptions options = SubmitOptions.defaults().withWriteBatchSize(10).withWriteQueueCapacity(20);
        JobMaster first = start(
                plan,
                Duration.ofSeconds(5),
                options,
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run));
        while (first.handle().status() != JobStatus.RUNNING
                || first.tasks().stream().anyMatch(t -> t.state() != TaskState.RUNNING)) {
            Thread.sleep(5);
        }
        Thread.sleep(200);
        var savepoint = first.triggerSavepoint("moved").get(20, TimeUnit.SECONDS);
        first.cancel();
        assertThat(end(first.handle()).status()).isEqualTo(JobStatus.CANCELLED);
        int committedBefore = Probe.get(probe).rows.size();
        assertThat(committedBefore)
                .as("the savepoint committed what came before it")
                .isBetween(1, 2999);
        awaitSlotsBack();

        JobMaster second = start(
                plan,
                Duration.ofSeconds(5),
                options,
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run),
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        savepoint.directory(), false, false));
        JobResult result = end(second.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertExactlyOnce(probe, 3000);
        assertThat(Probe.get(probe).events).anyMatch(e -> e.startsWith("enumerator restored with "));
        assertThat(Probe.get(probe).restoredStates)
                .as("every writer got its state")
                .hasSize(3);
        awaitSlotsBack();
    }

    @Test
    void aPausedRunHoldsNoSlotsAndGoesOnAsIfItHadNotPaused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        String probe = Probe.create();
        var storage = new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir);
        var store = new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore();
        JobPlan plan = withCheckpoints(
                TestConnectors.job(probe, "rows: 3000, splits: 12", null, "commit: transactional, slow-ms: 1", 3),
                Duration.ofHours(1),
                3,
                2);
        SubmitOptions options = SubmitOptions.defaults().withWriteBatchSize(10).withWriteQueueCapacity(20);
        JobMaster first = start(
                plan,
                Duration.ofSeconds(5),
                options,
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run));
        while (first.handle().status() != JobStatus.RUNNING
                || first.tasks().stream().anyMatch(t -> t.state() != TaskState.RUNNING)) {
            Thread.sleep(5);
        }
        Thread.sleep(200);

        var savepoint = first.pause("pause").get(20, TimeUnit.SECONDS);
        JobResult paused = end(first.handle());
        assertThat(paused.status()).isEqualTo(JobStatus.PAUSED);
        assertThat(first.pausedWith()).isEqualTo(savepoint);
        assertThat(Probe.get(probe).rows.size())
                .as("what came before the savepoint is committed")
                .isBetween(1, 2999);
        assertThat(Probe.get(probe).events).anyMatch(e -> e.startsWith("prepared"));
        awaitSlotsBack();

        JobMaster second = start(
                plan,
                Duration.ofSeconds(5),
                options,
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run),
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        savepoint.directory(), false, false));
        JobResult result = end(second.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertExactlyOnce(probe, 3000);
        awaitSlotsBack();
    }

    @Test
    void aPauseWhoseSavepointFailsLetsTheRunReadOn(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        String probe = Probe.create();
        var storage = new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir);
        Worker refusing = workers.values().iterator().next();
        // one worker refuses to stop: the savepoint fails
        refusing.barriers = trigger -> master.gateway()
                .declineCheckpoint(
                        trigger.task(),
                        trigger.checkpointId(),
                        org.devlive.pipeforge.common.error.PipeForgeException.of(ErrorCode.CHECKPOINT_DECLINED)
                                .param("checkpoint", trigger.checkpointId())
                                .param("task", trigger.task().toString())
                                .param("reason", "not now")
                                .build());
        JobMaster job = start(
                withCheckpoints(
                        TestConnectors.job(
                                probe, "rows: 3000, splits: 12", null, "commit: transactional, slow-ms: 1", 3),
                        Duration.ofHours(1),
                        3,
                        2),
                Duration.ofSeconds(5),
                SubmitOptions.defaults().withWriteBatchSize(10).withWriteQueueCapacity(20),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                        storage,
                        new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(),
                        Runnable::run));
        while (job.handle().status() != JobStatus.RUNNING
                || job.tasks().stream().anyMatch(t -> t.state() != TaskState.RUNNING)) {
            Thread.sleep(5);
        }

        assertThat(job.pause(null))
                .failsWithin(Duration.ofSeconds(20))
                .withThrowableThat()
                .havingRootCause()
                .withMessageContaining("not now");
        refusing.barriers = null;
        JobResult result = end(job.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertExactlyOnce(probe, 3000);
        assertThat(Probe.get(probe).events)
                .as("the readers that stopped read on")
                .noneMatch(e -> e.contains("paused"));
        awaitSlotsBack();
    }

    /** Takes a savepoint of a transactional run at {@code parallelism}, after some rows, and cancels the run. */
    org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Completed savepointAt(
            String probe, int parallelism, org.devlive.pipeforge.engine.master.checkpoint.Checkpoints checkpoints)
            throws Exception {
        JobMaster first = start(
                withCheckpoints(
                        TestConnectors.job(
                                probe,
                                "rows: 3000, splits: 12",
                                null,
                                "commit: transactional, slow-ms: 1",
                                parallelism),
                        Duration.ofHours(1),
                        3,
                        2),
                Duration.ofSeconds(5),
                SubmitOptions.defaults().withWriteBatchSize(10).withWriteQueueCapacity(20),
                checkpoints);
        while (first.handle().status() != JobStatus.RUNNING
                || first.tasks().stream().anyMatch(t -> t.state() != TaskState.RUNNING)) {
            Thread.sleep(5);
        }
        Thread.sleep(200);
        var savepoint = first.pause(null).get(20, TimeUnit.SECONDS);
        assertThat(end(first.handle()).status()).isEqualTo(JobStatus.PAUSED);
        awaitSlotsBack();
        return savepoint;
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2, 4", "4, 2", "3, 1"})
    void aRunGoesOnWithAnotherParallelismAndEveryRowOnce(
            int before, int after, @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String probe = Probe.create();
        var checkpoints = new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir),
                new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(),
                Runnable::run);
        var savepoint = savepointAt(probe, before, checkpoints);
        int writers = Probe.get(probe).events.stream()
                .filter(e -> e.startsWith("prepared "))
                .map(e -> e.substring(0, e.lastIndexOf('-')))
                .collect(java.util.stream.Collectors.toSet())
                .size();
        assertThat(writers).as("every writer prepared the savepoint").isEqualTo(before);

        JobMaster second = start(
                withCheckpoints(
                        TestConnectors.job(
                                probe, "rows: 3000, splits: 12", null, "commit: transactional, slow-ms: 1", after),
                        Duration.ofHours(1),
                        3,
                        2),
                Duration.ofSeconds(5),
                SubmitOptions.defaults().withWriteBatchSize(10).withWriteQueueCapacity(20),
                checkpoints,
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        savepoint.directory(), false, false));
        JobResult result = end(second.handle());
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        assertExactlyOnce(probe, 3000);
        Map<Integer, List<String>> restored = Probe.get(probe).restoredStates;
        assertThat(restored.values().stream().mapToInt(List::size).sum())
                .as("every old writer's state went to a new writer")
                .isEqualTo(before);
        assertThat(restored.keySet()).allMatch(subtask -> subtask < after);
        awaitSlotsBack();
    }

    @Test
    void aNodeWithStateThatIsGoneIsRefusedUnlessItsStateMayBeLeftOut(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String probe = Probe.create();
        var checkpoints = new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir),
                new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(),
                Runnable::run);
        var savepoint = savepointAt(probe, 3, checkpoints);
        // the sink is now another node: the state of "out" has no node to go to
        JobPlan renamed = withCheckpoints(
                TestConnectors.plan("version: 1\nname: test-job\nenv:\n  parallelism: 3\n"
                        + "source:\n  - { id: src, type: numbers, probe: " + probe + ", rows: 3000, splits: 12 }\n"
                        + "sink:\n  - { id: target, type: collect, probe: " + probe + " }\n"),
                Duration.ofHours(1),
                3,
                2);

        JobMaster refused = start(
                renamed,
                Duration.ofSeconds(5),
                SubmitOptions.defaults(),
                checkpoints,
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        savepoint.directory(), false, false));
        JobResult result = end(refused.handle());
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code()).isEqualTo(ErrorCode.STATE_INCOMPATIBLE);
        assertThat(result.error().getMessage()).contains("node 'out'");
        awaitSlotsBack();

        JobMaster allowed = start(
                renamed,
                Duration.ofSeconds(5),
                SubmitOptions.defaults(),
                checkpoints,
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        savepoint.directory(), true, false));
        assertThat(end(allowed.handle()).status()).isEqualTo(JobStatus.SUCCESS);
        awaitSlotsBack();
    }

    @Test
    void aRestorePointOfAnotherJobIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String probe = Probe.create();
        var storage = new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir);
        var store = new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore();
        JobMaster first = start(
                withCheckpoints(TestConnectors.job(probe, "endless: true", null, "", 3), Duration.ofHours(1), 3, 2),
                Duration.ofSeconds(5),
                SubmitOptions.defaults(),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run));
        while (first.handle().status() != JobStatus.RUNNING
                || first.tasks().stream().anyMatch(t -> t.state() != TaskState.RUNNING)) {
            Thread.sleep(5);
        }
        var savepoint = first.triggerSavepoint(null).get(20, TimeUnit.SECONDS);
        first.cancel();
        end(first.handle());
        awaitSlotsBack();

        JobPlan other = withCheckpoints(
                TestConnectors.plan("version: 1\nname: other-job\nenv:\n  parallelism: 3\n"
                        + "source:\n  - { id: src, type: numbers, probe: " + probe + ", endless: true }\n"
                        + "sink:\n  - { id: out, type: collect, probe: " + probe + " }\n"),
                Duration.ofHours(1),
                3,
                2);
        JobMaster foreign = start(
                other,
                Duration.ofSeconds(5),
                SubmitOptions.defaults(),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run),
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        savepoint.directory(), false, false));
        JobResult refused = end(foreign.handle());
        assertThat(refused.status()).isEqualTo(JobStatus.FAILED);
        assertThat(refused.error().code()).isEqualTo(ErrorCode.STATE_INCOMPATIBLE);
        assertThat(refused.error().getMessage()).contains("taken by job 'test-job', not 'other-job'");

        JobMaster missing = start(
                withCheckpoints(TestConnectors.job(probe, "endless: true", null, "", 3), Duration.ofHours(1), 3, 2),
                Duration.ofSeconds(5),
                SubmitOptions.defaults(),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run),
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        "nowhere/chk-9", false, false));
        JobResult notThere = end(missing.handle());
        assertThat(notThere.error().code()).isEqualTo(ErrorCode.STATE_CORRUPTED);
        awaitSlotsBack();
    }

    @Test
    void aRegionThatKeepsFailingFailsTheRunOnceTheRestartsAreUsedUp(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String probe = Probe.create();
        JobMaster job = startWithCheckpoints(
                TestConnectors.job(
                        probe,
                        "rows: 3000, splits: 12",
                        null,
                        "commit: transactional, fail-at: 10, fail-with: deadlock",
                        3),
                dir);

        JobResult result = end(job.handle());
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code()).isEqualTo(ErrorCode.SINK_FAILED);
        assertThat(job.restarts()).isEqualTo(3);
        assertThat(job.decision())
                .isInstanceOfSatisfying(
                        org.devlive.pipeforge.engine.master.failure.FailureHandler.Fail.class,
                        fail -> assertThat(fail.exhausted()).isTrue());
        awaitSlotsBack();
    }

    @Test
    void declinedCheckpointsFailTheRunOnceMoreFailedInARowThanTolerated(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var store = new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore();
        var storage = new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir);
        workers.values()
                .forEach(w -> w.barriers = trigger -> master.gateway()
                        .declineCheckpoint(
                                trigger.task(),
                                trigger.checkpointId(),
                                org.devlive.pipeforge.common.error.PipeForgeException.of(ErrorCode.CHECKPOINT_DECLINED)
                                        .param("checkpoint", trigger.checkpointId())
                                        .param("task", trigger.task().toString())
                                        .param("reason", "the disk is full")
                                        .build()));
        String probe = Probe.create();
        JobMaster job = start(
                withCheckpoints(TestConnectors.job(probe, "endless: true", "", "", 3), Duration.ofMillis(100), 1, 3),
                Duration.ofSeconds(5),
                SubmitOptions.defaults(),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run));

        JobResult result = end(job.handle());
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code()).isEqualTo(ErrorCode.TOO_MANY_CHECKPOINT_FAILURES);
        assertThat(result.error().getMessage()).contains("the disk is full");
        assertThat(store.rows.values()).hasSizeGreaterThanOrEqualTo(2).allSatisfy(c -> assertThat(c.errorCode())
                .isEqualTo("PF-CKPT-0005"));
        assertThat(storage.list("")).as("failed checkpoints leave nothing").isEmpty();
        awaitSlotsBack();
    }

    @Test
    void checkpointsOfThreeWorkersHoldTheReadersSplits(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var store = new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore();
        var storage = new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir);
        String probe = Probe.create();
        JobMaster job = start(
                withCheckpoints(
                        TestConnectors.job(probe, "rows: 600, splits: 6, endless: true", "", "", 3),
                        Duration.ofMillis(100),
                        0,
                        2),
                Duration.ofSeconds(5),
                SubmitOptions.defaults(),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (job.checkpointStats().lastCompletedId() < 4) {
            assertThat(System.nanoTime()).as("four checkpoints completed").isLessThan(deadline);
            Thread.sleep(20);
        }
        assertThat(job.handle().status()).isEqualTo(JobStatus.RUNNING);
        job.cancel();
        assertThat(end(job.handle()).status()).isEqualTo(JobStatus.CANCELLED);

        var kept = store.list(job.runId()).stream()
                .filter(c ->
                        c.status() == org.devlive.pipeforge.engine.master.checkpoint.CheckpointStore.Status.COMPLETED)
                .toList();
        assertThat(kept).as("two retained").hasSize(2);
        long last = kept.get(0).checkpointId();
        String directory = org.devlive.pipeforge.engine.api.checkpoint.CheckpointPaths.checkpoint(job.runId(), last);
        var metadata = org.devlive.pipeforge.engine.rpc.CheckpointFiles.readMetadata(
                storage.read(directory + "_metadata"), String.valueOf(last));
        assertThat(metadata.getOperatorsList())
                .extracting(o -> o.getOperatorKind(), o -> o.getSubtasksCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("enumerator", 1),
                        org.assertj.core.groups.Tuple.tuple("source", 3),
                        org.assertj.core.groups.Tuple.tuple("sink", 3),
                        org.assertj.core.groups.Tuple.tuple("committer", 3));
        assertThat(metadata.getOperators(1).getSubtasksList())
                .extracting(org.devlive.pipeforge.engine.rpc.checkpoint.proto.SubtaskState::getSubtask)
                .containsExactlyInAnyOrder(0, 1, 2);
        assertThat(kept.get(0).tasksJson()).contains("/src/0/0", "/src/1/0", "/src/2/0");
        assertThat(storage.isComplete(
                        org.devlive.pipeforge.engine.api.checkpoint.CheckpointPaths.checkpoint(job.runId(), 1)))
                .as("older checkpoints are deleted")
                .isFalse();
        assertThat(job.handle().metrics().lastCompletedCheckpointId())
                .isEqualTo(job.checkpointStats().lastCompletedId());
        awaitSlotsBack();
    }

    @Test
    void theCommittablesOfCompletedCheckpointsAreCommittedAndNoOthers(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var store = new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore();
        var storage = new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir);
        String probe = Probe.create();
        JobMaster job = start(
                withCheckpoints(
                        TestConnectors.job(probe, "rows: 600, splits: 6, endless: true", "", "commit: two-phase", 3),
                        Duration.ofMillis(100),
                        0,
                        2),
                Duration.ofSeconds(5),
                SubmitOptions.defaults(),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (job.checkpointStats().lastCompletedId() < 3) {
            assertThat(System.nanoTime()).as("three checkpoints completed").isLessThan(deadline);
            Thread.sleep(20);
        }
        job.cancel();
        assertThat(end(job.handle()).status()).isEqualTo(JobStatus.CANCELLED);

        java.util.Set<Long> completed = new java.util.HashSet<>();
        store.rows.values().stream()
                .filter(c ->
                        c.status() == org.devlive.pipeforge.engine.master.checkpoint.CheckpointStore.Status.COMPLETED)
                .forEach(c -> completed.add(c.checkpointId()));
        // checkpoints beyond the two retained were deleted from the store; their numbers are all lower
        long lowest = completed.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertThat(Probe.get(probe).committed).isNotEmpty().allSatisfy(c -> {
            long checkpoint = Long.parseLong(c.substring(c.indexOf('@') + 1));
            assertThat(checkpoint < lowest || completed.contains(checkpoint))
                    .as("%s belongs to a completed checkpoint", c)
                    .isTrue();
        });
        assertThat(Probe.get(probe).committed)
                .as("every sink committed for the last completed checkpoint")
                .filteredOn(c -> c.endsWith("@" + job.checkpointStats().lastCompletedId()))
                .hasSize(3);
        awaitSlotsBack();
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
