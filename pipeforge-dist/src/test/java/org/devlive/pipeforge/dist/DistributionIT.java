/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packaged distribution: its scripts start, report and stop a master and a worker, and a job
 * submitted to the master runs on the worker with plugins it downloads from the master.
 */
@DisabledOnOs(OS.WINDOWS)
class DistributionIT {

    @TempDir
    Path dir;

    Path home;
    final Map<String, String> environment = new HashMap<>();
    int masterPort;

    record Result(int exit, String out) {}

    /** Copies the packaged distribution, keeping the modes of the scripts. */
    @BeforeEach
    void install() throws Exception {
        Path dist = Path.of(System.getProperty("pipeforge.dist"));
        home = dir.resolve("pipeforge");
        try (Stream<Path> files = Files.walk(dist)) {
            for (Path file : files.toList()) {
                Path target = home.resolve(dist.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        int[] ports = ProcessTest.freePorts(3);
        masterPort = ports[0];
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        environment.put("JAVA_OPTS", "-Xms128m -Xmx512m");
        environment.put("PIPEFORGE_CLUSTER_TOKEN", UUID.randomUUID().toString());
        environment.put("PIPEFORGE_MASTER_RPC_PORT", String.valueOf(masterPort));
        environment.put("PIPEFORGE_WORKER_MASTER_ADDRESS", "localhost:" + masterPort);
        environment.put("PIPEFORGE_WORKER_HOST", "localhost");
        environment.put("PIPEFORGE_WORKER_RPC_PORT", String.valueOf(ports[1]));
        environment.put("PIPEFORGE_WORKER_DATA_PORT", String.valueOf(ports[2]));
        environment.put("PIPEFORGE_WORKER_SLOTS", "2");
    }

    @AfterEach
    void stopAll() throws Exception {
        for (String role : List.of("worker", "master", "standalone")) {
            script(role, "stop");
        }
    }

    Result script(String role, String... args) throws Exception {
        List<String> command = new ArrayList<>(
                List.of(home.resolve("bin/pipeforge-" + role + ".sh").toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), out);
    }

    @Test
    void startsAClusterRunsAJobAndStopsIt() throws Exception {
        Result master = script("master", "start");
        assertThat(master.exit()).as(master.out()).isZero();
        assertThat(master.out()).startsWith("Started the master (pid ");
        assertThat(script("master", "start").out()).startsWith("The master is running already");
        Result worker = script("worker", "start", "--labels", "zone=test");
        assertThat(worker.exit()).as(worker.out()).isZero();
        Result status = script("worker", "status");
        assertThat(status.exit()).isZero();
        assertThat(status.out()).startsWith("The worker is running (pid ");

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int submitted = SubmitMain.run(
                new String[] {
                    "../examples/fake-to-console.yaml", "--master", "localhost:" + masterPort, "--timeout", "1min"
                },
                environment,
                home,
                new java.io.PrintStream(out, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(submitted).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Submitted run ")
                .contains("  SUCCESS")
                .contains("users: 5 in, 5 out");
        // the worker downloaded the plugins of the job from the master and printed its rows
        try (Stream<Path> blobs = Files.list(home.resolve("data/worker/plugins/blobs"))) {
            assertThat(blobs.count()).isGreaterThanOrEqualTo(2);
        }
        assertThat(Files.readString(home.resolve("logs/pipeforge-worker.out"))).contains("| +I |");
        assertThat(Files.readString(home.resolve("logs/pipeforge-master.log"))).contains("Master of cluster default");
        assertThat(Files.readString(home.resolve("logs/pipeforge-worker.log"))).contains("offers 2 slot(s)");
        // every task wrote its own log, and the lines of the worker log name the task they come from
        String run = out.toString(StandardCharsets.UTF_8)
                .lines()
                .filter(l -> l.startsWith("Submitted run "))
                .findFirst()
                .orElseThrow()
                .split(" ")[2];
        List<Path> all;
        try (Stream<Path> files = Files.walk(home.resolve("logs"))) {
            all = files.toList();
        }
        List<Path> taskLogs = all.stream()
                .filter(f -> f.getParent().getFileName().toString().equals(run))
                .filter(f -> f.toString().endsWith("-0.log"))
                .toList();
        assertThat(taskLogs).as("task logs of run %s among %s", run, all).isNotEmpty();
        assertThat(taskLogs).allSatisfy(f -> assertThat(f.getParent())
                .as("<job name>/<run>/<file>")
                .isEqualTo(home.resolve("logs/tasks/fake-to-console").resolve(run)));
        for (Path taskLog : taskLogs) {
            assertThat(Files.readString(taskLog)).contains(" is running").contains(" finished");
        }
        assertThat(Files.readString(home.resolve("logs/pipeforge-worker.log"))).contains(" " + run + "/");

        Result stopped = script("worker", "stop");
        assertThat(stopped.out()).isEqualTo("Stopped the worker.\n");
        assertThat(script("worker", "status").exit()).isEqualTo(3);
        assertThat(script("master", "restart").exit()).isZero();
        assertThat(script("master", "stop").exit()).isZero();
        assertThat(script("master", "status").out()).isEqualTo("The master is not running.\n");
        assertThat(home.resolve("run/master.pid")).doesNotExist();
    }

    @Test
    void aProcessWithoutATokenDoesNotStart() throws Exception {
        environment.remove("PIPEFORGE_CLUSTER_TOKEN");
        Result result = script("standalone", "start");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out())
                .contains("did not start")
                .contains("PF-CONF-0011")
                .contains("cluster.token");
        assertThat(script("standalone", "status").exit()).isEqualTo(3);
        assertThat(script("standalone", "bogus").exit()).isEqualTo(2);
    }

    @Test
    void runsAStandaloneProcessInTheForegroundUntilItIsStopped() throws Exception {
        List<String> command =
                List.of(home.resolve("bin/pipeforge-standalone.sh").toString(), "run");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process =
                builder.redirectOutput(dir.resolve("standalone.out").toFile()).start();
        Path pid = home.resolve("run/standalone.pid");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!Files.exists(pid)) {
            assertThat(process.isAlive())
                    .as(
                            Files.exists(dir.resolve("standalone.out"))
                                    ? Files.readString(dir.resolve("standalone.out"))
                                    : "")
                    .isTrue();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(100);
        }
        assertThat(script("standalone", "status").exit()).isZero();
        process.destroy();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(pid).as("removed on SIGTERM").doesNotExist();
    }
}
