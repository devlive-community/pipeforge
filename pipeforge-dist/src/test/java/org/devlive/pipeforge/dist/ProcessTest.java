/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.dist.config.Configuration;
import org.devlive.pipeforge.engine.master.Scheduler;
import org.devlive.pipeforge.engine.worker.MasterConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The master and worker processes started in this JVM. */
class ProcessTest {

    @TempDir
    Path home;

    static int freePort() throws IOException {
        return freePorts(1)[0];
    }

    /** Distinct free ports: the sockets stay open until all are chosen, so no port comes twice. */
    static int[] freePorts(int count) throws IOException {
        ServerSocket[] sockets = new ServerSocket[count];
        try {
            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                sockets[i] = new ServerSocket(0);
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket socket : sockets) {
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }

    Map<String, String> settings(int[] ports) {
        return settings(ports[0], ports[1], ports[2]);
    }

    Map<String, String> settings(int master, int rpc, int data) {
        return Map.of(
                "cluster.token", "secret",
                "master.rpc.port", String.valueOf(master),
                "worker.master.address", "localhost:" + master,
                "worker.host", "localhost",
                "worker.rpc.port", String.valueOf(rpc),
                "worker.data.port", String.valueOf(data),
                "worker.slots", "3",
                "worker.labels", "zone=east",
                "master.heartbeat.interval", "200ms");
    }

    static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void aWorkerRegistersWithTheMasterAndOffersItsSlots() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        Configuration config = Configuration.load(home.resolve("conf"), Map.of(), settings(freePorts(3)));
        try (MasterProcess master = new MasterProcess(config, home);
                WorkerProcess worker = new WorkerProcess(config, home)) {
            await("the worker registers", () -> worker.state() == MasterConnection.State.CONNECTED);
            assertThat(master.resourceManager().workers()).singleElement().satisfies(w -> {
                assertThat(w.slots()).hasSize(3);
                assertThat(w.spec().labels()).containsEntry("zone", "east");
                assertThat(w.spec().dataAddress()).startsWith("localhost:");
            });
            assertThat(master.port())
                    .isEqualTo(config.integer(org.devlive.pipeforge.dist.config.Settings.MASTER_RPC_PORT));
            assertThat(master.dispatcher().list(java.util.Set.of(), 10)).isEmpty();
        }
    }

    @Test
    void theNetworkMemoryIsDerivedFromTheHeap() {
        Configuration config =
                Configuration.load(home.resolve("conf"), Map.of("PIPEFORGE_CLUSTER_TOKEN", "t"), Map.of());
        long memory = WorkerProcess.networkOptions(config).memoryBytes();
        assertThat(memory).isBetween(64L << 20, 1L << 30);
        assertThat(WorkerProcess.networkOptions(Configuration.load(
                                home.resolve("conf"),
                                Map.of(),
                                Map.of("cluster.token", "t", "worker.memory.network", "16MB")))
                        .memoryBytes())
                .isEqualTo(16L << 20);
        assertThat(MasterProcess.strategy("PACK")).isEqualTo(Scheduler.Strategy.PACK);
        assertThatThrownBy(() -> MasterProcess.strategy("random"))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SETTING_INVALID));
        assertThat(WorkerProcess.hostName()).isNotBlank();
    }

    @Test
    void standaloneRunsAMasterAndAWorkerAndWritesItsProcessId() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        Map<String, String> settings = settings(freePorts(3));
        String[] args = settings.entrySet().stream()
                .flatMap(e -> java.util.stream.Stream.of("--set", e.getKey() + "=" + e.getValue()))
                .toArray(String[]::new);
        String[] all = new String[args.length + 1];
        all[0] = "standalone";
        System.arraycopy(args, 0, all, 1, args.length);
        CountDownLatch stop = new CountDownLatch(1);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(
                () -> ClusterMain.run(all, Map.of(), home, new PrintStream(err, true, StandardCharsets.UTF_8), stop));
        Path pid = home.resolve("run/standalone.pid");
        await("the process ID is written", () -> Files.exists(pid) || run.isDone());
        assertThat(run).as(err.toString(StandardCharsets.UTF_8)).isNotDone();
        assertThat(Files.readString(pid).trim())
                .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
        stop.countDown();
        assertThat(run.get(30, TimeUnit.SECONDS)).isZero();
        assertThat(pid).doesNotExist();
    }

    @Test
    void wrongArgumentsAndSettingsStopTheStart() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(err, true, StandardCharsets.UTF_8);
        CountDownLatch never = new CountDownLatch(1);
        assertThat(ClusterMain.run(new String[] {"server"}, Map.of(), home, stream, never))
                .isEqualTo(2);
        assertThat(ClusterMain.run(new String[] {"master", "--set", "oops"}, Map.of(), home, stream, never))
                .isEqualTo(2);
        assertThat(ClusterMain.run(new String[] {"master", "--bogus"}, Map.of(), home, stream, never))
                .isEqualTo(2);
        assertThat(ClusterMain.run(new String[] {"master"}, Map.of(), home, stream, never))
                .isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("usage: <master|worker|standalone>")
                .contains("is not --set key=value")
                .contains("unknown argument '--bogus'")
                .contains("PF-CONF-0011");
        // a port that is taken
        try (ServerSocket taken = new ServerSocket(0)) {
            int code = ClusterMain.run(
                    new String[] {
                        "master", "--set", "cluster.token=t", "--set", "master.rpc.port=" + taken.getLocalPort()
                    },
                    Map.of(),
                    home,
                    stream,
                    never);
            assertThat(code).isEqualTo(1);
        }
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("PF-RES-0006");
        assertThat(ClusterMain.parse(new String[] {"worker", "--labels", "zone=east"})
                        .overrides())
                .containsEntry("worker.labels", "zone=east");
        assertThat(ClusterMain.home()).isAbsolute();
        assertThat(Duration.ZERO).isZero();
    }
}
