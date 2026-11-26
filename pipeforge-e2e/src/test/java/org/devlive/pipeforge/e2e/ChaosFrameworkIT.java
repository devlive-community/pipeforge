/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.e2e.chaos.Chaos;
import org.devlive.pipeforge.e2e.chaos.ChaosJar;
import org.devlive.pipeforge.e2e.chaos.ChaosPlan;
import org.devlive.pipeforge.e2e.chaos.ChaosRecord;
import org.devlive.pipeforge.e2e.chaos.FaultyCheckpointStorageFactory;
import org.devlive.pipeforge.e2e.chaos.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The chaos framework on a cluster of the distribution: two workers reach the master through
 * proxies and keep checkpoints in the faulty storage, and a short plan kills, stops and cuts them
 * off and makes the storage fail. Afterwards every fault was injected and healed and the cluster
 * is whole again. The scenarios of the recovery tests build on this.
 */
@DisabledOnOs(OS.WINDOWS)
class ChaosFrameworkIT {

    @Test
    void injectsEveryFaultOfItsPlanAndTheClusterComesBack() throws Exception {
        Path dist = Path.of(System.getProperty("pipeforge.dist"));
        Path dir = dist.getParent().getParent().resolve("chaos-run");
        Support.deleteTree(dir);
        Files.createDirectories(dir);
        long seed = ChaosPlan.runSeed();
        Path control = dir.resolve("storage.control");
        Path checkpoints = Files.createDirectories(dir.resolve("checkpoints"));
        String storage = FaultyCheckpointStorageFactory.location(checkpoints, control);

        try (Cluster cluster = new Cluster(dist, dir);
                ClusterFaults faults = new ClusterFaults(cluster, control, seed)) {
            cluster.addJar(ChaosJar.write(dir.resolve("pipeforge-chaos.jar")));
            Path master = cluster.startMaster(Duration.ofSeconds(4));
            InetSocketAddress toMaster = new InetSocketAddress("localhost", cluster.masterPort());
            for (String worker : List.of("worker-1", "worker-2")) {
                cluster.startWorker(
                        worker,
                        2,
                        "zone=" + worker,
                        faults.link(worker + "->master", toMaster),
                        Map.of("PIPEFORGE_CHECKPOINT_DIR", storage));
            }
            cluster.startServer(master);
            cluster.await("/api/v1/cluster", c -> c.path("workers").size() == 2, Duration.ofMinutes(2));

            ChaosPlan plan = ChaosPlan.of(
                    seed,
                    new ChaosPlan.Settings(
                            Duration.ofSeconds(40),
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(4),
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(5)),
                    List.of(
                            Target.process("worker-1"),
                            Target.process("worker-2"),
                            faults.linkTarget("worker-1->master"),
                            faults.linkTarget("worker-2->master"),
                            Target.storage("checkpoints")));
            Chaos chaos = new Chaos(
                    "framework", plan, faults, dist.getParent().getParent().resolve("chaos"));
            try {
                chaos.start().awaitEnd(Duration.ofMinutes(5));

                List<ChaosRecord.Event> events = chaos.record().events();
                assertThat(events).hasSize(2 * plan.injections().size());
                assertThat(events)
                        .allSatisfy(e -> assertThat(e.error()).as(e.toString()).isNull());
                assertThat(Files.readAllLines(chaos.record().file())).hasSize(1 + events.size());
                assertThat(plan.injections())
                        .isEqualTo(ChaosPlan.of(seed, settings(plan), plan.targets())
                                .injections());

                // healed: both workers run, are connected and send heartbeats again
                assertThat(cluster.alive("worker-1") && cluster.alive("worker-2"))
                        .isTrue();
                JsonNode whole = cluster.await(
                        "/api/v1/cluster",
                        c -> c.path("workers").size() == 2
                                && c.path("workers").findValues("sinceHeartbeatMs").stream()
                                        .allMatch(h -> h.asLong() < 3_000),
                        Duration.ofMinutes(2));
                assertThat(whole.path("workers").findValuesAsText("rpcAddress")).hasSize(2);
                assertThat(Files.readString(dir.resolve("logs/worker-1.out")))
                        .as("the worker opened the faulty storage")
                        .doesNotContain("PF-CKPT-0004");
            } catch (AssertionError | Exception e) {
                String view;
                try {
                    view = String.valueOf(cluster.get("/api/v1/cluster"));
                } catch (Exception unreachable) {
                    view = "the server did not answer: " + unreachable.getMessage();
                }
                chaos.keep(dir.resolve("logs"), checkpoints, Map.of("cluster.json", view));
                throw e;
            } finally {
                chaos.close();
            }
        }
    }

    private static ChaosPlan.Settings settings(ChaosPlan plan) {
        return new ChaosPlan.Settings(plan.length(), plan.minGap(), plan.maxGap(), plan.minFault(), plan.maxFault());
    }
}
