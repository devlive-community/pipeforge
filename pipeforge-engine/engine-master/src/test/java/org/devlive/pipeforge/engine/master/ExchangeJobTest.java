/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.api.JobId;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.master.graph.JobGraph;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.network.NetworkOptions;
import org.devlive.pipeforge.engine.rpc.InProcessRpc;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.Partitioning;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.devlive.pipeforge.engine.worker.SlotTable;
import org.devlive.pipeforge.engine.worker.TaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jobs whose tasks exchange rows over TCP, on three workers with a data exchange each. */
class ExchangeJobTest {

    static final class Worker {
        final NetworkEnvironment network;
        final InetSocketAddress data;
        final TaskExecutor executor;
        final InProcessRpc.Endpoint<TaskExecutorGateway> endpoint;
        final SlotTable slots;
        String id = "";

        Worker(String name, JobMasterGateway master) {
            network = new NetworkEnvironment(
                    name, "token", NetworkOptions.defaults().withMemory(16L << 20, 4096));
            data = network.start("127.0.0.1", 0);
            slots = new SlotTable(name, 2);
            executor = new TaskExecutor(name, master, slots, network);
            endpoint = InProcessRpc.serve(name, executor);
        }

        void close() {
            executor.close();
            endpoint.close();
            network.close();
        }
    }

    final AtomicLong now = new AtomicLong();
    final ResourceManager manager = new ResourceManager(ResourceManager.Settings.DEFAULTS, now::get);
    final JobMasters jobs = new JobMasters();
    final InProcessRpc.Endpoint<JobMasterGateway> master = InProcessRpc.serve("master", jobs);
    final Map<String, Worker> workers = new ConcurrentHashMap<>();
    final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();
    final Scheduler scheduler = new Scheduler(
            manager, slot -> workers.get(slot.workerId()).endpoint.gateway(), thread, Duration.ofMillis(10));

    @BeforeEach
    void start() {
        manager.addListener(jobs);
        for (int i = 1; i <= 3; i++) {
            Worker worker = new Worker("worker-" + i, master.gateway());
            worker.id = manager.register(new ResourceManager.WorkerSpec(
                            "0.1.0",
                            "worker-" + i + ":6123",
                            "127.0.0.1:" + worker.data.getPort(),
                            new ResourceManager.Resources(2, 1L << 30, 0, 0),
                            2,
                            Map.of(),
                            "default",
                            new byte[32],
                            null))
                    .workerId();
            workers.put(worker.id, worker);
        }
    }

    @AfterEach
    void stop() {
        workers.values().forEach(Worker::close);
        master.close();
        thread.shutdownNow();
    }

    JobMaster start(JobPlan plan, SubmitOptions options) {
        return start(plan, options, null);
    }

    JobMaster start(
            JobPlan plan,
            SubmitOptions options,
            org.devlive.pipeforge.engine.master.checkpoint.@org.jspecify.annotations.Nullable Checkpoints checkpoints) {
        JobId id = JobId.random();
        JobMaster job = new JobMaster(
                id,
                plan,
                ChainTopology.graph(plan),
                options,
                Placement.cluster(
                        scheduler,
                        slot -> workers.get(slot.workerId()).endpoint.gateway(),
                        Map.of(),
                        Scheduler.Strategy.SPREAD,
                        Duration.ofSeconds(5)),
                () -> jobs.remove(id.value()),
                checkpoints);
        jobs.add(job);
        job.start();
        return job;
    }

    JobResult run(JobPlan plan) throws Exception {
        return start(plan, SubmitOptions.defaults()).handle().result().get(60, TimeUnit.SECONDS);
    }

    static JobPlan pipeline(String probe, String source, int sourceParallelism, String sink, int sinkParallelism) {
        return TestConnectors.plan(
                """
                version: 1
                name: exchange
                source:
                  - { id: src, type: numbers, probe: %s, parallelism: %d, %s }
                sink:
                  - { id: out, type: collect, probe: %s, parallelism: %d%s }
                """
                        .formatted(probe, sourceParallelism, source, probe, sinkParallelism, sink));
    }

    static Set<Long> ids(List<Row> rows) {
        return rows.stream().map(r -> (Long) r.get(0)).collect(Collectors.toCollection(TreeSet::new));
    }

    void awaitIdle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!(manager.freeSlots(Map.of()).size() == 6
                && workers.values().stream()
                        .allMatch(w -> w.network.partitions().size() == 0))) {
            assertThat(System.nanoTime()).as("slots and outputs given back").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void rebalancesTheRowsOfTwoReadersOverThreeWriters() throws Exception {
        String probe = Probe.create();
        JobPlan plan = pipeline(probe, "rows: 3000, splits: 6", 2, "", 3);
        JobGraph graph = ChainTopology.graph(plan);
        assertThat(graph.edges()).singleElement().satisfies(edge -> assertThat(edge.partitioning())
                .isEqualTo(Partitioning.REBALANCE));

        JobResult result = run(plan);

        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        Probe seen = Probe.get(probe);
        assertThat(seen.rows).hasSize(3000);
        assertThat(ids(seen.rows)).hasSize(3000).first().isEqualTo(0L);
        assertThat(seen.rowsBySubtask.keySet()).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(seen.rowsBySubtask.values())
                .allSatisfy(rows -> assertThat(rows).hasSizeGreaterThan(500));
        assertThat(result.metrics().rowsRead()).isEqualTo(3000);
        assertThat(result.metrics().rowsWritten()).isEqualTo(3000);
        assertThat(result.metrics().reconciled()).isTrue();
        assertThat(result.metrics().parallelism()).isEqualTo(5);
        // the rows crossed tasks: what the source vertex sent is what the sink vertex received
        JobMetricsCheck.exchange(result.metrics(), "src", "out", 6);
        assertThat(workers.values()).as("the channels cross workers").anySatisfy(w -> assertThat(
                        w.network.transport().openConnections())
                .isPositive());
        awaitIdle();
    }

    /** The subtask every id was written by. */
    Map<Long, Integer> hashed(String probe) {
        Map<Long, Integer> owners = new TreeMap<>();
        Probe.get(probe).rowsBySubtask.forEach((subtask, rows) -> {
            for (Row row : rows) {
                Integer previous = owners.put((Long) row.get(0), subtask);
                assertThat(previous).as("id %s reaches one writer", row.get(0)).isNull();
            }
        });
        return owners;
    }

    @Test
    void theWritersOfAHashExchangeAlignTheBarriersOfBothReaders(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var store = new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore();
        var storage = new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir);
        String probe = Probe.create();
        JobPlan plan = ClusterJobTest.withCheckpoints(
                pipeline(probe, "rows: 20000, splits: 8, unbounded: true, endless: true", 2, "", 3),
                Duration.ofMillis(100),
                0,
                3);
        assertThat(ChainTopology.graph(plan).edges())
                .singleElement()
                .satisfies(edge -> assertThat(edge.partitioning().kind()).isEqualTo(Partitioning.Kind.HASH));
        JobMaster job = start(
                plan,
                SubmitOptions.defaults(),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(storage, store, Runnable::run));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (job.checkpointStats().lastCompletedId() < 3) {
            assertThat(System.nanoTime()).as("three checkpoints completed").isLessThan(deadline);
            Thread.sleep(20);
        }
        job.cancel();
        assertThat(job.handle().result().get(30, TimeUnit.SECONDS).status()).isEqualTo(JobStatus.CANCELLED);

        var completed = store.list(job.runId()).stream()
                .filter(c ->
                        c.status() == org.devlive.pipeforge.engine.master.checkpoint.CheckpointStore.Status.COMPLETED)
                .toList();
        assertThat(completed).isNotEmpty().allSatisfy(c -> assertThat(c.tasksJson())
                .as("both readers and all three writers took part")
                .contains("/src/0/0", "/src/1/0", "/out/0/0", "/out/1/0", "/out/2/0"));
        long last = completed.get(0).checkpointId();
        var metadata = org.devlive.pipeforge.engine.rpc.CheckpointFiles.readMetadata(
                storage.read(org.devlive.pipeforge.engine.api.checkpoint.CheckpointPaths.checkpoint(job.runId(), last)
                        + "_metadata"),
                String.valueOf(last));
        assertThat(metadata.getOperatorsList())
                .extracting(o -> o.getOperatorKind(), o -> o.getSubtasksCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("enumerator", 1),
                        org.assertj.core.groups.Tuple.tuple("source", 2),
                        org.assertj.core.groups.Tuple.tuple("sink", 3),
                        org.assertj.core.groups.Tuple.tuple("committer", 3));
        awaitIdle();
    }

    @Test
    void aHashExchangeRestartsBothSidesTogetherAndCommitsEveryRowOnce(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String probe = Probe.create();
        JobPlan plan = ClusterJobTest.withRestarts(
                ClusterJobTest.withCheckpoints(
                        pipeline(
                                probe,
                                "rows: 3000, splits: 8, unbounded: true",
                                2,
                                ", commit: transactional, slow-ms: 1, fail-at: 100, fail-subtask: 1, fail-times: 1,"
                                        + " fail-with: deadlock",
                                3),
                        Duration.ofMillis(100),
                        3,
                        2),
                3,
                Duration.ofMillis(50));
        JobMaster job = start(
                plan,
                SubmitOptions.defaults().withWriteBatchSize(10).withWriteQueueCapacity(20),
                new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                        new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir),
                        new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(),
                        Runnable::run));

        JobResult result = job.handle().result().get(60, TimeUnit.SECONDS);
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        ClusterJobTest.assertExactlyOnce(probe, 3000);
        assertThat(job.restarts()).isOne();
        assertThat(job.tasks())
                .as("the readers and writers of an exchange form one region")
                .hasSize(5)
                .allSatisfy(t -> assertThat(t.attempt().attempt()).isOne());
        awaitIdle();
    }

    @Test
    void sendsTheChangesOfAKeyToOneWriterOnEveryRun() throws Exception {
        String first = Probe.create();
        JobPlan plan = pipeline(first, "rows: 2000, splits: 4, unbounded: true", 2, "", 3);
        assertThat(ChainTopology.graph(plan).edges())
                .singleElement()
                .satisfies(edge -> assertThat(edge.partitioning().kind()).isEqualTo(Partitioning.Kind.HASH));

        JobResult result = run(plan);
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
        Map<Long, Integer> owners = hashed(first);
        assertThat(owners).hasSize(2000);
        assertThat(new TreeSet<>(owners.values())).containsExactly(0, 1, 2);

        String second = Probe.create();
        JobResult again = run(pipeline(second, "rows: 2000, splits: 4, unbounded: true", 2, "", 3));
        assertThat(again.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(hashed(second)).as("the same key goes to the same writer").isEqualTo(owners);
        awaitIdle();
    }

    @Test
    void aFailingWriterFailsTheJobAndStopsTheReaders() throws Exception {
        String probe = Probe.create();
        JobResult result = run(pipeline(probe, "rows: 100000, splits: 4", 2, ", fail-at: 50", 3));
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error()).isNotNull();
        assertThat(result.error().scope().node()).isEqualTo("out");
        awaitIdle();
    }

    @Test
    void cancelsTheTasksOnBothSidesOfAnExchange() throws Exception {
        String probe = Probe.create();
        JobMaster job =
                start(pipeline(probe, "rows: 100000, splits: 4", 2, ", block: cancel", 3), SubmitOptions.defaults());
        assertThat(Probe.get(probe).blocking.await(20, TimeUnit.SECONDS)).isTrue();
        job.handle().cancel();
        JobResult result = job.handle().result().get(60, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.tasks()).hasSize(5).allSatisfy(t -> assertThat(t.state().isTerminal())
                .isTrue());
        awaitIdle();
    }

    @Test
    void aBrokenDataConnectionFailsTheJob() throws Exception {
        String probe = Probe.create();
        JobMaster job =
                start(pipeline(probe, "rows: 10000000, splits: 4", 2, ", slow-ms: 1", 3), SubmitOptions.defaults());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (Probe.get(probe).writerThreads.isEmpty()) {
            assertThat(System.nanoTime()).as("rows reach the writers").isLessThan(deadline);
            Thread.sleep(10);
        }
        List<Worker> all = new ArrayList<>(workers.values());
        all.forEach(w -> w.network.transport().abortAccepted());
        JobResult result = job.handle().result().get(60, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code()).isEqualTo(ErrorCode.DATA_CONNECTION_LOST);
        awaitIdle();
    }
}
