/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.devlive.pipeforge.engine.master.ResourceManager;
import org.devlive.pipeforge.engine.master.ResourceManagerService;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.grpc.ClusterToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A worker's connection to a real resource manager over gRPC, with short heartbeat intervals. */
class MasterConnectionTest {

    static final ClusterToken TOKEN = new ClusterToken("cluster-secret");
    static final ExecutionAttemptId TASK = new ExecutionAttemptId("run", "src", 0, 0);
    static final ResourceManager.Settings FAST =
            new ResourceManager.Settings(Duration.ofMillis(50), Duration.ofMillis(600), 1);

    /** Tasks that run until they are cancelled. */
    static final class Tasks implements LocalTasks {
        final AtomicInteger cancelled = new AtomicInteger();

        @Override
        public synchronized Map<ExecutionAttemptId, TaskState> taskStates() {
            return cancelled.get() == 0 ? Map.of(TASK, TaskState.RUNNING) : Map.of();
        }

        @Override
        public synchronized void cancelAll() {
            cancelled.incrementAndGet();
        }
    }

    final String name = "master-" + UUID.randomUUID();
    final Tasks tasks = new Tasks();
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    ResourceManager manager;
    Server server;
    ManagedChannel channel;
    MasterConnection connection;

    @BeforeEach
    void start() throws Exception {
        startMaster();
        channel =
                InProcessChannelBuilder.forName(name).intercept(TOKEN.client()).build();
        connection = new MasterConnection(
                channel,
                new MasterConnection.Config(
                        "worker-1:6124", "worker-1:6125", 3, Map.of("zone", "east"), "etl", Duration.ofMillis(20)),
                new byte[32],
                tasks);
        connection.start(scheduler);
    }

    void startMaster() throws Exception {
        manager = new ResourceManager(FAST);
        server = InProcessServerBuilder.forName(name)
                .intercept(TOKEN.server())
                .addService(new ResourceManagerService(manager))
                .build()
                .start();
    }

    void stopMaster() throws InterruptedException {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws InterruptedException {
        connection.close();
        scheduler.shutdownNow();
        channel.shutdownNow();
        stopMaster();
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waiting for " + what).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void registersOffersItsSlotsAndStaysAlive() throws Exception {
        await("registration", () -> connection.state() == MasterConnection.State.CONNECTED);
        String id = connection.workerId();
        await(
                "the first heartbeat",
                () -> !manager.workers().isEmpty()
                        && !manager.workers().get(0).tasks().isEmpty());

        ResourceManager.Worker worker = manager.workers().get(0);
        assertThat(worker.id()).isEqualTo(id);
        assertThat(worker.spec().rpcAddress()).isEqualTo("worker-1:6124");
        assertThat(worker.spec().dataAddress()).isEqualTo("worker-1:6125");
        assertThat(worker.spec().labels()).containsEntry("zone", "east");
        assertThat(worker.spec().resourceGroup()).isEqualTo("etl");
        assertThat(worker.spec().publicKey()).hasSize(32);
        assertThat(worker.freeSlots()).isEqualTo(3);
        assertThat(worker.tasks()).containsExactly(new ResourceManager.RunningTask(TASK, TaskState.RUNNING));

        // twice the timeout: the heartbeats keep the worker in the cluster
        Thread.sleep(1_200);
        assertThat(manager.expire()).isEmpty();
        assertThat(manager.workers()).extracting(ResourceManager.Worker::id).containsExactly(id);
        assertThat(connection.state()).isEqualTo(MasterConnection.State.CONNECTED);
        assertThat(tasks.cancelled).hasValue(0);
    }

    @Test
    void registersAgainWithItsIdWhenTheMasterRestarted() throws Exception {
        await("registration", () -> connection.state() == MasterConnection.State.CONNECTED);
        String id = connection.workerId();

        stopMaster();
        startMaster();
        await("registration with the new master", () -> !manager.workers().isEmpty());

        assertThat(manager.workers()).extracting(ResourceManager.Worker::id).containsExactly(id);
        assertThat(connection.workerId()).isEqualTo(id);
        assertThat(tasks.cancelled)
                .as("the master came back before the timeout")
                .hasValue(0);
    }

    @Test
    void cancelsItsTasksWhenTheMasterIsGoneAndComesBack() throws Exception {
        await("registration", () -> connection.state() == MasterConnection.State.CONNECTED);
        String id = connection.workerId();

        stopMaster();
        await("the loss of the master", () -> connection.state() == MasterConnection.State.LOST);
        assertThat(tasks.cancelled).hasValue(1);

        startMaster();
        await("registration again", () -> connection.state() == MasterConnection.State.CONNECTED);
        assertThat(connection.workerId()).isEqualTo(id);
        assertThat(manager.workers()).extracting(ResourceManager.Worker::id).containsExactly(id);
        assertThat(tasks.cancelled).as("cancelled once").hasValue(1);
    }

    @Test
    void validatesItsConfiguration() {
        MasterConnection.Config defaults = MasterConnection.Config.defaults("w:6124", "w:6125");
        assertThat(defaults.slots()).isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(defaults.labels()).isEmpty();
        assertThat(defaults.retryInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThatThrownBy(() -> new MasterConnection.Config("w:1", "w:2", 0, Map.of(), "", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("worker.slots must be at least 1, not 0");
        assertThat(MasterConnection.engineVersion()).isNotBlank();
    }
}
