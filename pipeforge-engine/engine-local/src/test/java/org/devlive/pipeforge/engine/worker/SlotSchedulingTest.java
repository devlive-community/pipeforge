/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.engine.master.ResourceManager;
import org.devlive.pipeforge.engine.master.ResourceManagerService;
import org.devlive.pipeforge.engine.master.Scheduler;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.ReaderMessage;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.rpc.grpc.ClusterToken;
import org.devlive.pipeforge.engine.rpc.grpc.Fencing;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.IdempotencyCache;
import org.devlive.pipeforge.engine.rpc.grpc.Retry;
import org.devlive.pipeforge.engine.rpc.grpc.SecretBox;
import org.devlive.pipeforge.engine.rpc.grpc.TaskExecutorClient;
import org.devlive.pipeforge.engine.rpc.grpc.TaskExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A master and a worker over gRPC: the scheduler reserves slots on the worker's task executor, the
 * worker's heartbeats confirm them in the ledger, and releasing them frees them on both sides.
 */
class SlotSchedulingTest {

    static final ClusterToken TOKEN = new ClusterToken("cluster-secret");

    static final JobMasterGateway NO_JOB_MASTER = new JobMasterGateway() {
        @Override
        public java.util.concurrent.CompletableFuture<Void> updateTaskState(TaskStateUpdate update) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> sendReaderMessage(ReaderMessage message) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> reportMetrics(
                ExecutionAttemptId task, TaskMetricsSnapshot metrics) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    };

    final String masterName = "master-" + UUID.randomUUID();
    final String workerName = "worker-" + UUID.randomUUID();
    final ScheduledExecutorService threads = Executors.newScheduledThreadPool(2);
    final ResourceManager manager =
            new ResourceManager(new ResourceManager.Settings(Duration.ofMillis(30), Duration.ofSeconds(5), 1));
    final SlotTable slots = new SlotTable("worker-1:6124", 2);
    final SecretBox keys = SecretBox.generate();
    TaskExecutor executor;
    Server master;
    Server worker;
    ManagedChannel toMaster;
    ManagedChannel toWorker;
    MasterConnection connection;
    Scheduler scheduler;

    @BeforeEach
    void start() throws Exception {
        master = InProcessServerBuilder.forName(masterName)
                .intercept(TOKEN.server())
                .addService(new ResourceManagerService(manager))
                .build()
                .start();
        executor = new TaskExecutor("worker-1", NO_JOB_MASTER, slots);
        worker = InProcessServerBuilder.forName(workerName)
                .intercept(TOKEN.server())
                .addService(new TaskExecutorService(
                        executor, keys, (kind, type, plugin) -> null, new Fencing(), new IdempotencyCache()))
                .build()
                .start();
        toMaster = InProcessChannelBuilder.forName(masterName)
                .intercept(TOKEN.client())
                .build();
        toWorker = InProcessChannelBuilder.forName(workerName)
                .intercept(TOKEN.client())
                .build();
        connection = new MasterConnection(
                toMaster,
                new MasterConnection.Config(
                        "worker-1:6124", "worker-1:6125", 2, Map.of(), "default", Duration.ofMillis(20)),
                keys.publicKey(),
                executor,
                slots);
        connection.start(threads);
        TaskExecutorClient client = new TaskExecutorClient(
                toWorker,
                "worker-1:6124",
                keys.publicKey(),
                () -> Headers.Epochs.FIRST,
                new Retry(threads, Duration.ofMillis(5)));
        scheduler = new Scheduler(manager, slot -> client, threads, Duration.ofMillis(10));
    }

    @AfterEach
    void stop() throws InterruptedException {
        connection.close();
        executor.close();
        threads.shutdownNow();
        toMaster.shutdownNow();
        toWorker.shutdownNow();
        master.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        worker.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void reservedSlotsAreConfirmedByTheWorkerAndFreedOnBothSides() throws Exception {
        MasterConnectionTest.await("registration", () -> !manager.workers().isEmpty());

        Scheduler.Allocation allocation = scheduler
                .allocate(
                        "job-1",
                        "run-1",
                        new Scheduler.Requirements(2, Map.of(), Scheduler.Strategy.SPREAD, Duration.ofSeconds(5)))
                .get(5, TimeUnit.SECONDS);

        assertThat(slots.snapshot()).allSatisfy(s -> assertThat(s.jobId()).isEqualTo("job-1"));
        // the worker's heartbeats now report the reservations the master made
        Thread.sleep(200);
        assertThat(manager.workers().get(0).slots())
                .extracting(ResourceManager.Slot::allocationId)
                .containsExactlyInAnyOrderElementsOf(allocation.slots().stream()
                        .map(Scheduler.Assignment::allocationId)
                        .toList());

        scheduler.release(allocation);
        MasterConnectionTest.await(
                "release on the worker", () -> slots.snapshot().stream().noneMatch(SlotTable.Slot::allocated));
        Thread.sleep(200);
        assertThat(manager.freeSlots(Map.of())).hasSize(2);
    }
}
