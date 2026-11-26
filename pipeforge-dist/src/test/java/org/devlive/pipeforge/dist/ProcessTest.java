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
    void theMasterRemovesAWorkerThatStoppedSendingHeartbeats() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        Map<String, String> settings = new java.util.HashMap<>(settings(freePorts(3)));
        settings.put("master.heartbeat.timeout", "1s");
        Configuration config = Configuration.load(home.resolve("conf"), Map.of(), settings);
        try (MasterProcess master = new MasterProcess(config, home)) {
            // registered, then silent, as a worker whose process was killed
            master.resourceManager()
                    .register(new org.devlive.pipeforge.engine.master.ResourceManager.WorkerSpec(
                            "0.1.0",
                            "localhost:1",
                            "localhost:2",
                            new org.devlive.pipeforge.engine.master.ResourceManager.Resources(1, 1, 1, 1),
                            1,
                            Map.of(),
                            "",
                            new byte[0],
                            null));
            assertThat(master.resourceManager().workers()).hasSize(1);
            await(
                    "the silent worker is removed",
                    () -> master.resourceManager().workers().isEmpty());
        }
    }

    /** An H2 metastore with the tables the server's migrations create. */
    static String migratedMetastore(String name) throws Exception {
        String url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
        Path scripts = Path.of("..", "pipeforge-server", "src", "main", "resources", "db", "metastore", "expand", "h2");
        try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement();
                var files = Files.list(scripts)) {
            for (Path script : files.sorted(java.util.Comparator.comparing(
                            p -> Integer.parseInt(p.getFileName().toString().replaceAll("^V(\\d+)__.*", "$1"))))
                    .toList()) {
                for (String sql :
                        Files.readString(script).replaceAll("(?m)^--.*$", "").split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
        return url;
    }

    @Test
    void theMastersOfAClusterElectOneLeaderAndTheNextOneFencesTheFirst() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        int[] ports = freePorts(4);
        String metastore = migratedMetastore("election");
        Map<String, String> first = new java.util.HashMap<>(settings(ports[0], ports[2], ports[3]));
        first.put("master.metastore.url", metastore);
        first.put("master.metastore.username", "sa");
        first.put("master.host", "localhost");
        first.put("master.leader.lease", "1s");
        Map<String, String> second = new java.util.HashMap<>(first);
        second.put("master.rpc.port", String.valueOf(ports[1]));
        Configuration a = Configuration.load(home.resolve("conf"), Map.of(), first);
        Configuration b = Configuration.load(home.resolve("conf"), Map.of(), second);
        // the worker knows both masters, the standby first: it finds the leader and follows it
        Map<String, String> both = new java.util.HashMap<>(first);
        both.put("worker.master.address", "localhost:" + ports[1] + ",localhost:" + ports[0]);
        Configuration w = Configuration.load(home.resolve("conf"), Map.of(), both);
        var threads = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        MasterProcess leader = new MasterProcess(a, home);
        try (MasterProcess standby = new MasterProcess(b, home);
                WorkerProcess worker = new WorkerProcess(w, home)) {
            java.util.List<String> lost = new java.util.concurrent.CopyOnWriteArrayList<>();
            leader.onLeadershipLost(() -> lost.add("a"));
            assertThat(leader.isLeader()).isTrue();
            assertThat(leader.leaderEpoch()).isEqualTo(1);
            assertThat(standby.isLeader()).isFalse();
            await("the worker registers with the leader", () -> worker.state() == MasterConnection.State.CONNECTED);
            assertThat(leader.resourceManager().workers()).hasSize(1);

            // the standby refuses registrations and names the leader
            var security = Security.of(b, home);
            var toStandby =
                    org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport.channel("localhost:" + ports[1], security);
            try {
                assertThatThrownBy(
                                () -> org.devlive.pipeforge.engine.rpc.proto.ResourceManagerServiceGrpc.newBlockingStub(
                                                toStandby)
                                        .registerWorker(
                                                org.devlive.pipeforge.engine.rpc.proto.RegisterWorkerRequest
                                                        .getDefaultInstance()))
                        .satisfies(e -> {
                            PipeForgeException refused =
                                    org.devlive.pipeforge.engine.rpc.grpc.RpcErrors.fromStatus(e, "the standby");
                            assertThat(refused.code()).isEqualTo(ErrorCode.NOT_LEADER);
                            assertThat(refused.params())
                                    .containsEntry("leader", "localhost:" + ports[0])
                                    .containsEntry("master", "localhost:" + ports[1]);
                        });
            } finally {
                toStandby.shutdownNow();
            }

            // the leader stops and gives its lease up: the standby leads with the next epoch
            leader.close();
            await("the standby takes over", standby::isLeader);
            assertThat(standby.leaderEpoch()).isEqualTo(2);
            assertThat(lost).as("closing is not losing the lead").isEmpty();
            await(
                    "the worker follows the new leader",
                    () -> !standby.resourceManager().workers().isEmpty());
            await("the worker is connected", () -> worker.state() == MasterConnection.State.CONNECTED);

            // once a worker heard of epoch 2, requests of the master of epoch 1 are refused
            var toWorker = org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport.channel(
                    "localhost:" + ports[2], Security.of(a, home));
            try {
                var retry = new org.devlive.pipeforge.engine.rpc.grpc.Retry(threads, Duration.ofMillis(50));
                var current = new org.devlive.pipeforge.engine.rpc.grpc.TaskExecutorClient(
                        toWorker,
                        "worker",
                        new byte[32],
                        () -> new org.devlive.pipeforge.engine.rpc.grpc.Headers.Epochs(2, 1),
                        retry);
                var replaced = new org.devlive.pipeforge.engine.rpc.grpc.TaskExecutorClient(
                        toWorker,
                        "worker",
                        new byte[32],
                        () -> new org.devlive.pipeforge.engine.rpc.grpc.Headers.Epochs(1, 1),
                        retry);
                current.freeSlot("none").get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> replaced.freeSlot("none").get(10, TimeUnit.SECONDS))
                        .satisfies(e -> assertThat(
                                        PipeForgeException.wrap(e.getCause()).code())
                                .isEqualTo(ErrorCode.RPC_STALE_EPOCH));
            } finally {
                toWorker.shutdownNow();
            }
        } finally {
            leader.close();
            threads.shutdownNow();
        }
    }

    @Test
    void aLeaderAskedToFailOverHandsItsLeaseToTheStandbyAndStops() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        int[] ports = freePorts(4);
        String metastore = migratedMetastore("failover");
        Map<String, String> first = new java.util.HashMap<>(settings(ports[0], ports[2], ports[3]));
        first.put("master.metastore.url", metastore);
        first.put("master.metastore.username", "sa");
        first.put("master.host", "localhost");
        first.put("master.leader.lease", "1s");
        Map<String, String> second = new java.util.HashMap<>(first);
        second.put("master.rpc.port", String.valueOf(ports[1]));
        Configuration a = Configuration.load(home.resolve("conf"), Map.of(), first);
        try (MasterProcess leader = new MasterProcess(a, home);
                MasterProcess standby =
                        new MasterProcess(Configuration.load(home.resolve("conf"), Map.of(), second), home)) {
            CountDownLatch stopped = new CountDownLatch(1);
            leader.onLeadershipLost(stopped::countDown);
            var header = org.devlive.pipeforge.engine.rpc.grpc.Headers.create(
                    org.devlive.pipeforge.engine.rpc.grpc.Headers.Epochs.FIRST,
                    Duration.ofSeconds(10),
                    System::currentTimeMillis);
            var request = org.devlive.pipeforge.engine.rpc.proto.ClusterRequest.newBuilder()
                    .setHeader(header)
                    .build();
            var security = Security.of(a, home);
            var toStandby =
                    org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport.channel("localhost:" + ports[1], security);
            var toLeader =
                    org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport.channel("localhost:" + ports[0], security);
            try {
                // a standby has no lead to hand over
                assertThatThrownBy(() -> org.devlive.pipeforge.engine.rpc.proto.DispatcherServiceGrpc.newBlockingStub(
                                        toStandby)
                                .releaseLeadership(request))
                        .satisfies(
                                e -> assertThat(org.devlive.pipeforge.engine.rpc.grpc.RpcErrors.fromStatus(e, "standby")
                                                .code())
                                        .isEqualTo(ErrorCode.NOT_LEADER));

                org.devlive.pipeforge.engine.rpc.proto.DispatcherServiceGrpc.newBlockingStub(toLeader)
                        .releaseLeadership(request);
                assertThat(stopped.await(10, TimeUnit.SECONDS))
                        .as("the old leader stops")
                        .isTrue();
                assertThat(leader.isLeader()).isFalse();
                await("the standby takes over", standby::isLeader);
                assertThat(standby.leaderEpoch()).isEqualTo(2);
            } finally {
                toStandby.shutdownNow();
                toLeader.shutdownNow();
            }
        }

        // a master without a metastore cannot hand over
        Map<String, String> alone = new java.util.HashMap<>(settings(freePorts(3)));
        try (MasterProcess single =
                new MasterProcess(Configuration.load(home.resolve("conf"), Map.of(), alone), home)) {
            assertThatThrownBy(single::handOver)
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                            .isEqualTo(ErrorCode.FAILOVER_UNAVAILABLE));
        }
    }

    @Test
    void processesCheckTheirCheckpointStorageAtTheStart() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        Map<String, String> settings = new java.util.HashMap<>(settings(freePorts(3)));
        Path checkpoints = home.resolve("checkpoints");
        settings.put("checkpoint.dir", checkpoints.toString());
        settings.put("master.metastore.url", migratedMetastore("process-test"));
        settings.put("master.metastore.username", "sa");
        Configuration config = Configuration.load(home.resolve("conf"), Map.of(), settings);
        try (MasterProcess master = new MasterProcess(config, home);
                WorkerProcess worker = new WorkerProcess(config, home)) {
            assertThat(master.checkpointStorage())
                    .hasValueSatisfying(s -> assertThat(s.location()).isEqualTo(checkpoints.toUri()));
            assertThat(worker.checkpointStorage()).isPresent();
            // the probes left nothing behind
            assertThat(master.checkpointStorage().orElseThrow().list("")).isEmpty();
        }

        settings.put("checkpoint.dir", "hdfs://namenode/checkpoints");
        Configuration unknown = Configuration.load(home.resolve("conf"), Map.of(), settings);
        assertThatThrownBy(() -> new MasterProcess(unknown, home))
                .isInstanceOfSatisfying(
                        org.devlive.pipeforge.common.error.PipeForgeException.class, e -> assertThat(e.getMessage())
                                .startsWith("PF-CKPT-0004 The checkpoint storage hdfs://namenode/checkpoints cannot be"
                                        + " used: no checkpoint storage takes 'hdfs:' locations; known: file, s3"));

        settings.put("checkpoint.dir", " ");
        try (MasterProcess master =
                new MasterProcess(Configuration.load(home.resolve("conf"), Map.of(), settings), home)) {
            assertThat(master.checkpointStorage()).as("blank is not set").isEmpty();
        }
    }

    @Test
    void aMasterWithCheckpointStorageNeedsAMetastoreWithTheCheckpointTable() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        Map<String, String> settings = new java.util.HashMap<>(settings(freePorts(3)));
        settings.put("checkpoint.dir", home.resolve("checkpoints").toString());
        Configuration without = Configuration.load(home.resolve("conf"), Map.of(), settings);
        assertThatThrownBy(() -> new MasterProcess(without, home))
                .isInstanceOfSatisfying(org.devlive.pipeforge.common.error.PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(org.devlive.pipeforge.common.error.ErrorCode.SETTING_INVALID);
                    assertThat(e.params()).containsEntry("key", "master.metastore.url");
                });

        settings.put("master.metastore.url", "jdbc:h2:mem:empty-metastore;DB_CLOSE_DELAY=-1");
        settings.put("master.metastore.username", "sa");
        Configuration empty = Configuration.load(home.resolve("conf"), Map.of(), settings);
        assertThatThrownBy(() -> new MasterProcess(empty, home))
                .hasMessageContaining("PF-CLUS-0013")
                .hasMessageContaining("has no checkpoint table");

        settings.put("master.metastore.url", "jdbc:postgresql://127.0.0.1:1/none?user=x&password=secret");
        settings.put("master.metastore.pool-size", "1");
        Configuration unreachable = Configuration.load(home.resolve("conf"), Map.of(), settings);
        assertThatThrownBy(() -> new MasterProcess(unreachable, home))
                .hasMessageContaining("PF-CLUS-0013")
                .hasMessageNotContaining("secret");

        // the first attempts released their port: the master starts on it now
        settings.put("master.metastore.url", migratedMetastore("master-ok"));
        try (MasterProcess master =
                new MasterProcess(Configuration.load(home.resolve("conf"), Map.of(), settings), home)) {
            assertThat(master.checkpointStorage()).isPresent();
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
    void theHeapGuardIsOnByDefaultAndItsPercentsMustBeOrdered() {
        Configuration defaults =
                Configuration.load(home.resolve("conf"), Map.of("PIPEFORGE_CLUSTER_TOKEN", "t"), Map.of());
        assertThat(WorkerProcess.heapGuard(defaults))
                .isEqualTo(org.devlive.pipeforge.engine.worker.HeapGuard.Settings.DEFAULTS);
        Configuration off = Configuration.load(
                home.resolve("conf"), Map.of(), Map.of("cluster.token", "t", "worker.heap-guard.enabled", "false"));
        assertThat(WorkerProcess.heapGuard(off).enabled()).isFalse();
        Configuration reversed = Configuration.load(
                home.resolve("conf"),
                Map.of(),
                Map.of(
                        "cluster.token",
                        "t",
                        "worker.heap-guard.pause-percent",
                        "95",
                        "worker.heap-guard.fail-percent",
                        "90"));
        assertThatThrownBy(() -> WorkerProcess.heapGuard(reversed))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SETTING_INVALID));
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
