/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.RunAnalyzer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commands that run jobs on a cluster, against a stub of the server. */
class RunClusterTest {

    static final Path GOLDEN = Path.of("src/test/resources/golden");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ID = "8f6c1b2e-0000-4000-8000-000000000001";

    @TempDir
    Path dir;

    Shell shell;
    HttpServer server;
    final Map<String, Supplier<String>> routes = new ConcurrentHashMap<>();
    final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        shell = new Shell(dir);
        shell.variables.put(
                "PIPEFORGE_SERVER", "http://127.0.0.1:" + server.getAddress().getPort());
        shell.file(
                "jobs/users.yaml",
                """
                version: 1
                name: users
                source:
                  - { id: src, type: fake, columns: [id INT64 NOT NULL], rows: 5 }
                sink:
                  - { id: out, type: console }
                """);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange exchange) throws IOException {
        String key =
                exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(key
                + (exchange.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + exchange.getRequestURI().getRawQuery())
                + (body.isEmpty() ? "" : " " + body));
        Supplier<String> route = routes.get(key);
        byte[] answer = (route == null
                        ? "{\"code\":\"PF-CONF-0010\",\"message\":\"There is no run " + key + "\"}"
                        : route.get())
                .getBytes(StandardCharsets.UTF_8);
        int status = route == null ? 404 : statuses.getOrDefault(key, 200);
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, answer.length);
        exchange.getResponseBody().write(answer);
        exchange.close();
    }

    static String run(String status, String extra) {
        return """
                {"id": "%s", "job": "users", "status": "%s", "submittedAt": "2026-11-17T08:00:00Z",
                 "startedAt": "2026-11-17T08:00:01Z"%s}
                """
                .formatted(ID, status, extra);
    }

    static final String ENDED = run("SUCCESS", ", \"endedAt\": \"2026-11-17T08:03:06.500Z\"");

    static final String METRICS =
            """
            {"elapsedMs": 185500, "parallelism": 2, "rowsRead": 1234567, "rowsWritten": 1234560,
             "rowsInFlight": 7, "rowsDirty": 3, "readRowsPerSecond": 6655.2, "writeRowsPerSecond": 6655.1,
             "readerWaitMs": 92000, "writerWaitMs": 400, "throttleWaitMs": 0, "backpressuredMs": 15,
             "splitsTotal": 8, "splitsFinished": 8,
             "nodes": [{"node": "src", "rowsIn": 0, "rowsOut": 1234567, "rowsFiltered": 0, "rowsDirty": 3},
                       {"node": "out", "rowsIn": 1234560, "rowsOut": 1234560, "rowsFiltered": 0, "rowsDirty": 0}]}
            """;

    /** A run analysis the server could send: a real one, with the bottleneck of a slow target. */
    static String analysis() throws IOException {
        ObjectNode json =
                (ObjectNode) JSON.readTree(RunAnalyzer.analyze("users", JobStatus.SUCCESS, List.of(JobMetrics.EMPTY))
                        .json());
        json.set(
                "bottleneck",
                JSON.readTree(
                        """
                {"kind": "TARGET", "node": "out",
                 "evidence": [{"metric": "readerWaitShare", "node": null, "value": 0.5}],
                 "advice": [{"code": "sink-parallelism", "text": "Raise the parallelism of out (now 2)"},
                            {"code": "check-target", "text": "Check the write latency and the load of the target system"}]}
                """));
        return json.toString();
    }

    void serveEndedRun() throws IOException {
        routes.put("GET /api/v1/runs/" + ID, () -> "{\"run\": " + ENDED + ", \"transitions\": []}");
        routes.put("GET /api/v1/runs/" + ID + "/metrics", () -> METRICS);
        String analysis = analysis();
        routes.put("GET /api/v1/runs/" + ID + "/analysis", () -> analysis);
    }

    static void golden(String name, String actual) throws IOException {
        Path file = GOLDEN.resolve(name);
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(file, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void runsAJobOnTheClusterUntilItEnds() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        statuses.put("POST /api/v1/runs", 201);
        routes.put("POST /api/v1/runs", () -> run("QUEUE", ""));
        routes.put(
                "GET /api/v1/runs/" + ID,
                () -> "{\"run\": " + (asked.incrementAndGet() < 2 ? run("RUNNING", "") : ENDED) + "}");
        routes.put("GET /api/v1/runs/" + ID + "/metrics", () -> METRICS);

        Shell.Result result = shell.run("run", "jobs/users.yaml", "--var", "day=2026-11-17");
        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out())
                .isEqualTo("Job users SUCCESS in 3m05s: read 1,234,567 rows, wrote 1,234,560 rows (6,655 rows/s)\n");
        assertThat(result.err()).startsWith("Submitted run " + ID + " to http://127.0.0.1:");
        assertThat(received.get(0))
                .startsWith("POST /api/v1/runs {\"definition\":\"version: 1\\nname: users\\n")
                .endsWith("\"variables\":{\"day\":\"2026-11-17\"}}");
    }

    @Test
    void aFailedRunExitsWith4AndSaysWhy() {
        statuses.put("POST /api/v1/runs", 201);
        routes.put("POST /api/v1/runs", () -> run("QUEUE", ""));
        routes.put(
                "GET /api/v1/runs/" + ID,
                () -> "{\"run\": "
                        + run(
                                "FAILED",
                                """
                , "endedAt": "2026-11-17T08:00:07Z",
                "error": {"code": "PF-CLUS-0001", "message": "Worker w1 (h:1) sent no heartbeat for 6 s"}""")
                        + "}");
        routes.put("GET /api/v1/runs/" + ID + "/metrics", () -> METRICS);
        Shell.Result result = shell.run("run", "jobs/users.yaml");
        assertThat(result.exit()).isEqualTo(ExitCode.RUN_FAILED.code());
        assertThat(result.out()).endsWith("ERROR PF-CLUS-0001 Worker w1 (h:1) sent no heartbeat for 6 s\n");
    }

    @Test
    void detachedOnlySubmitsAndAnInvalidJobExitsWith3() {
        statuses.put("POST /api/v1/runs", 201);
        routes.put("POST /api/v1/runs", () -> run("QUEUE", ""));
        Shell.Result detached = shell.run("run", "--detach", "jobs/users.yaml");
        assertThat(detached.exit()).isZero();
        assertThat(detached.out()).isEqualTo(ID + "\n");

        statuses.put("POST /api/v1/runs", 422);
        routes.put(
                "POST /api/v1/runs",
                () ->
                        """
                {"code": "PF-PLUG-0001", "message": "The sink plugin 'kafka' is not installed",
                 "hint": "Install it on the server"}
                """);
        Shell.Result invalid = shell.run("run", "jobs/users.yaml");
        assertThat(invalid.exit()).isEqualTo(ExitCode.INVALID.code());
        assertThat(invalid.err())
                .isEqualTo("ERROR PF-PLUG-0001 The sink plugin 'kafka' is not installed\n  Install it on the server\n");
    }

    @Test
    void showsTheStatusOfARun() throws Exception {
        serveEndedRun();
        Shell.Result text = shell.run("run", "status", ID);
        assertThat(text.exit()).isZero();
        golden("run-status.txt", text.out());
        Shell.Result json = shell.run("run", "status", ID, "-o", "json");
        assertThat(JSON.readTree(json.out()).path("run").path("status").asText())
                .isEqualTo("SUCCESS");
        assertThat(JSON.readTree(json.out()).path("metrics").path("splitsTotal").asInt())
                .isEqualTo(8);
        assertThat(JSON.readTree(json.out())
                        .path("analysis")
                        .path("bottleneck")
                        .path("kind")
                        .asText())
                .isEqualTo("TARGET");
    }

    @Test
    void showsMetricsAndTheAnalysis() throws Exception {
        serveEndedRun();
        Shell.Result metrics = shell.run("run", "metrics", ID);
        assertThat(metrics.exit()).isZero();
        golden("run-metrics.txt", metrics.out());
        Shell.Result analyze = shell.run("run", "analyze", ID);
        assertThat(analyze.exit()).isZero();
        assertThat(analyze.out()).contains("TARGET").contains("Raise the parallelism of out (now 2)");
    }

    @Test
    void anUnknownRunExitsWith6() {
        Shell.Result result = shell.run("run", "status", "nope");
        assertThat(result.exit()).isEqualTo(ExitCode.NOT_FOUND.code());
        assertThat(result.err()).startsWith("ERROR PF-CONF-0010 There is no run GET /api/v1/runs/nope");
    }

    @Test
    void watchesARunAsJsonLinesWhenNotOnATerminal() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        routes.put(
                "GET /api/v1/runs/" + ID,
                () -> "{\"run\": " + (asked.incrementAndGet() < 3 ? run("RUNNING", "") : ENDED) + "}");
        routes.put("GET /api/v1/runs/" + ID + "/metrics", () -> METRICS);
        Shell.Result result = shell.run("run", "watch", ID, "--interval", "10ms");
        assertThat(result.exit()).isZero();
        List<String> lines = result.out().lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(JSON.readTree(lines.get(0)).path("status").asText()).isEqualTo("RUNNING");
        assertThat(JSON.readTree(lines.get(2)).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(JSON.readTree(lines.get(2)).path("splitsFinished").asInt()).isEqualTo(8);
    }

    @Test
    void showsTheLogsOfTasks() {
        routes.put(
                "GET /api/v1/runs/" + ID + "/tasks",
                () ->
                        """
                [{"vertex": "src", "subtask": 0, "attempt": 0, "state": "FINISHED", "worker": "w1"},
                 {"vertex": "src", "subtask": 1, "attempt": 0, "state": "FINISHED", "worker": "w2"}]
                """);
        routes.put(
                "GET /api/v1/runs/" + ID + "/logs",
                () -> "{\"text\": \"2026-11-17 08:00:02.000 WARN  slow\\n\", \"offset\": 34, \"eof\": true}");
        Shell.Result all = shell.run("run", "logs", ID);
        assertThat(all.exit()).isZero();
        assertThat(all.out())
                .isEqualTo("==> src[0] on w1 <==\n2026-11-17 08:00:02.000 WARN  slow\n"
                        + "==> src[1] on w2 <==\n2026-11-17 08:00:02.000 WARN  slow\n");
        Shell.Result one = shell.run("run", "logs", ID, "--task", "src[1]", "--level", "warn", "--grep", "slow down");
        assertThat(one.out()).isEqualTo("2026-11-17 08:00:02.000 WARN  slow\n");
        assertThat(received)
                .last()
                .isEqualTo("GET /api/v1/runs/" + ID
                        + "/logs?vertex=src&subtask=1&attempt=0&offset=0&contains=slow%20down&level=WARN");
        assertThat(shell.run("run", "logs", ID, "--task", "dst").err())
                .startsWith("Error: The run has no task dst; its tasks are src[0], src[1].");
        assertThat(shell.run("run", "logs", ID, "-f").err())
                .startsWith("Error: -f follows one task: choose it with --task, one of src[0], src[1].");
        assertThat(shell.run("run", "logs", ID, "--level", "loud").exit()).isEqualTo(ExitCode.USAGE.code());
    }

    @Test
    void cancelsOnlyWhenConfirmed() {
        routes.put("POST /api/v1/runs/" + ID + "/cancel", () -> run("CANCELLING", ""));
        Shell.Result unconfirmed = shell.run("run", "cancel", ID);
        assertThat(unconfirmed.exit()).isEqualTo(ExitCode.USAGE.code());
        assertThat(unconfirmed.err()).startsWith("Error: Cancelling run " + ID + " needs --yes");
        assertThat(received).isEmpty();

        Shell.Result cancelled = shell.run("run", "cancel", ID, "--yes", "--reason", "by hand");
        assertThat(cancelled.exit()).isZero();
        assertThat(cancelled.out()).isEqualTo("Run " + ID + " is CANCELLING.\n");
        assertThat(received).containsExactly("POST /api/v1/runs/" + ID + "/cancel {\"reason\":\"by hand\"}");
    }

    @Test
    void speaksChinese() throws Exception {
        serveEndedRun();
        shell.variables.put("PIPEFORGE_LANG", "zh-CN");
        Shell.Result result = shell.run("run", "status", ID);
        assertThat(result.out()).contains("进度   已读完 8 / 8 个分片（100%）").contains("瓶颈 TARGET：目标端最慢 (out)");
    }

    static final String CLUSTER =
            """
            {"master": {"address": "master:6123", "version": "0.1.0", "protocol": "1.0"},
             "slots": 8, "slotsUsed": 3, "usage": 0.375,
             "workers": [
               {"id": "w2", "rpcAddress": "h2:6124", "dataAddress": "h2:6125", "labels": {},
                "cpuCores": 4, "taskHeapBytes": 2147483648, "managedBytes": 0, "networkBytes": 0,
                "tasksRunning": 0, "sinceHeartbeatMs": 21000, "draining": true,
                "slots": [{"index": 0, "allocated": false}, {"index": 1, "allocated": false}]},
               {"id": "w1", "rpcAddress": "h1:6124", "dataAddress": "h1:6125",
                "labels": {"zone": "a", "disk": "ssd"}, "cpuCores": 16, "taskHeapBytes": 8589934592,
                "managedBytes": 0, "networkBytes": 0, "tasksRunning": 3, "sinceHeartbeatMs": 1200,
                "slots": [{"index": 0, "allocated": true, "job": "users"}, {"index": 1, "allocated": true, "job": "users"},
                          {"index": 2, "allocated": true, "job": "users"}, {"index": 3, "allocated": false},
                          {"index": 4, "allocated": false}, {"index": 5, "allocated": false}]}]}
            """;

    @Test
    void showsTheClusterAndItsWorkers() throws Exception {
        routes.put("GET /api/v1/cluster", () -> CLUSTER);
        Shell.Result status = shell.run("cluster", "status");
        assertThat(status.exit()).isZero();
        assertThat(status.out())
                .isEqualTo("Master   master:6123  version 0.1.0  protocol 1.0  up -\n"
                        + "Slots    3 of 8 in use (38%)\n"
                        + "Workers  2 connected, 1 late (no heartbeat for over 15 s)\n");
        Shell.Result workers = shell.run("cluster", "workers");
        assertThat(workers.exit()).isZero();
        golden("cluster-workers.txt", workers.out());
        Shell.Result json = shell.run("cluster", "workers", "-o", "json");
        assertThat(JSON.readTree(json.out()).get(0).path("id").asText()).isEqualTo("w2");
        assertThat(shell.run("cluster").exit()).isEqualTo(ExitCode.USAGE.code());
    }

    @Test
    void drainsAWorkerAndWaitsForItsTasks() throws Exception {
        statuses.put("POST /api/v1/cluster/workers/w1/drain", 204);
        routes.put("POST /api/v1/cluster/workers/w1/drain", () -> "");
        statuses.put("POST /api/v1/cluster/workers/w1/uncordon", 204);
        routes.put("POST /api/v1/cluster/workers/w1/uncordon", () -> "");
        Shell.Result drained = shell.run("cluster", "drain", "w1");
        assertThat(drained.exit()).as(drained.err()).isZero();
        assertThat(drained.out()).isEqualTo("Worker w1 takes no new tasks; its running tasks finish where they are.\n");

        // its three tasks end after two looks
        AtomicInteger looks = new AtomicInteger();
        routes.put(
                "GET /api/v1/cluster",
                () -> looks.incrementAndGet() < 3
                        ? CLUSTER
                        : CLUSTER.replace("\"tasksRunning\": 3", "\"tasksRunning\": 0"));
        Shell.Result waited = shell.run("cluster", "drain", "w1", "--wait", "--poll", "10ms");
        assertThat(waited.exit()).as(waited.err()).isZero();
        assertThat(waited.err()).contains("Worker w1 still runs 3 task(s)...");
        assertThat(waited.out()).isEqualTo("Worker w1 is drained: no task runs on it.\n");

        routes.put("GET /api/v1/cluster", () -> CLUSTER);
        Shell.Result late = shell.run("cluster", "drain", "w1", "--wait", "--timeout", "50ms", "--poll", "10ms");
        assertThat(late.exit()).isEqualTo(ExitCode.TIMEOUT.code());
        assertThat(late.err()).contains("Worker w1 still runs 3 task(s) after");

        Shell.Result back = shell.run("cluster", "uncordon", "w1", "-o", "json");
        assertThat(back.exit()).isZero();
        assertThat(JSON.readTree(back.out()).path("draining").asBoolean(true)).isFalse();
        assertThat(received)
                .anyMatch(r -> r.startsWith("POST /api/v1/cluster/workers/w1/drain"))
                .anyMatch(r -> r.startsWith("POST /api/v1/cluster/workers/w1/uncordon"));

        statuses.put("POST /api/v1/cluster/workers/w9/drain", 404);
        routes.put(
                "POST /api/v1/cluster/workers/w9/drain",
                () -> "{\"code\":\"PF-CLUS-0020\",\"message\":\"No worker w9 is registered with the master\"}");
        Shell.Result unknown = shell.run("cluster", "drain", "w9");
        assertThat(unknown.exit()).isEqualTo(ExitCode.NOT_FOUND.code());
        assertThat(unknown.err()).isEqualTo("ERROR PF-CLUS-0020 No worker w9 is registered with the master\n");
    }

    @Test
    void failsOverOnlyWhenAskedToWithoutATerminal() throws Exception {
        statuses.put("POST /api/v1/cluster/failover", 202);
        routes.put("POST /api/v1/cluster/failover", () -> "{\"master\": \"master-1:6123\"}");
        Shell.Result refused = shell.run("cluster", "failover");
        assertThat(refused.exit()).isEqualTo(ExitCode.USAGE.code());
        assertThat(refused.err()).contains("Failing over needs --yes");
        assertThat(received).noneMatch(r -> r.startsWith("POST /api/v1/cluster/failover"));

        Shell.Result done = shell.run("cluster", "failover", "--yes");
        assertThat(done.exit()).as(done.err()).isZero();
        assertThat(done.out())
                .isEqualTo("Master master-1:6123 gave its lease up: a standby master takes over within seconds, and"
                        + " master-1:6123 stops.\n");

        statuses.put("POST /api/v1/cluster/failover", 409);
        routes.put(
                "POST /api/v1/cluster/failover",
                () -> "{\"code\":\"PF-CLUS-0019\",\"message\":\"Master m cannot hand over\"}");
        Shell.Result alone = shell.run("cluster", "failover", "-y");
        assertThat(alone.exit()).isEqualTo(ExitCode.ERROR.code());
        assertThat(alone.err()).isEqualTo("ERROR PF-CLUS-0019 Master m cannot hand over\n");
    }

    static final String CHECKPOINTS =
            """
            [{"id": 9, "attempt": 2, "type": "SAVEPOINT", "status": "COMPLETED", "reason": "PAUSE",
              "triggeredAt": "2026-11-26T08:10:00Z", "completedAt": "2026-11-26T08:10:01.250Z", "sizeBytes": 2048,
              "path": "run/sp-9/", "tasks": []},
             {"id": 8, "attempt": 2, "type": "CHECKPOINT", "status": "FAILED", "reason": "PERIODIC",
              "triggeredAt": "2026-11-26T08:09:00Z", "path": "run/chk-8/",
              "error": {"code": "PF-CKPT-0001", "message": "Checkpoint 8 timed out"}, "tasks": []},
             {"id": 7, "attempt": 1, "type": "SAVEPOINT", "status": "COMPLETED", "reason": "SAVEPOINT",
              "name": "before-upgrade", "triggeredAt": "2026-11-26T08:05:00Z", "completedAt": "2026-11-26T08:05:00.400Z",
              "sizeBytes": 1536, "path": "run/sp-7/", "tasks": [{"task": "r/src/0/0", "states": 1, "syncMs": 2,
              "asyncMs": 3, "alignmentMs": 0, "stateBytes": 1536}]}]
            """;

    @Test
    void showsTheCheckpointsAndAttemptsOfARun() throws Exception {
        routes.put("GET /api/v1/runs/" + ID + "/checkpoints", () -> CHECKPOINTS);
        Shell.Result checkpoints = shell.run("run", "checkpoints", ID);
        assertThat(checkpoints.exit()).as(checkpoints.err()).isZero();
        golden("run-checkpoints.txt", checkpoints.out());
        Shell.Result json = shell.run("run", "checkpoints", ID, "-o", "json");
        assertThat(JSON.readTree(json.out())
                        .get(2)
                        .path("tasks")
                        .get(0)
                        .path("syncMs")
                        .asInt())
                .isEqualTo(2);
        routes.put("GET /api/v1/runs/" + ID + "/checkpoints", () -> "[]");
        assertThat(shell.run("run", "checkpoints", ID).out()).isEqualTo("Run " + ID + " has no checkpoints.\n");

        routes.put(
                "GET /api/v1/runs/" + ID + "/attempts",
                () ->
                        """
                [{"attempt": 1, "status": "FAILED", "startedAt": "2026-11-26T08:00:00Z", "endedAt": "2026-11-26T08:04:00Z",
                  "rowsRead": 1234567, "rowsWritten": 1234000, "elapsedMs": 240000,
                  "error": {"code": "PF-CONN-0003", "message": "The connection was lost"}},
                 {"attempt": 2, "status": "RUNNING", "startedAt": "2026-11-26T08:05:00Z", "rowsRead": 50,
                  "rowsWritten": 50, "elapsedMs": 61000, "restorePath": "run/chk-6/"}]
                """);
        Shell.Result attempts = shell.run("run", "attempts", ID);
        assertThat(attempts.exit()).as(attempts.err()).isZero();
        golden("run-attempts.txt", attempts.out());
        routes.put(
                "GET /api/v1/runs/" + ID + "/attempts",
                () ->
                        """
                [{"attempt": 1, "status": "FAILED", "startedAt": "2026-11-26T08:00:00Z",
                  "retryAt": "2999-01-01T00:00:00Z", "error": {"code": "PF-CONN-0003", "message": "lost"}}]
                """);
        assertThat(shell.run("run", "attempts", ID).out()).contains("next attempt in");
    }

    @Test
    void pausesResumesTakesSavepointsAndRetriesRuns() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        statuses.put("POST /api/v1/runs/" + ID + "/pause", 202);
        routes.put("POST /api/v1/runs/" + ID + "/pause", () -> run("PAUSING", ""));
        routes.put(
                "GET /api/v1/runs/" + ID,
                () -> "{\"run\": " + run(asked.incrementAndGet() < 3 ? "PAUSING" : "PAUSED", "")
                        + ", \"transitions\": []}");
        Shell.Result asking = shell.run("run", "pause", ID);
        assertThat(asking.exit()).as(asking.err()).isZero();
        assertThat(asking.out()).isEqualTo("Run " + ID + " is PAUSING.\n");
        Shell.Result paused = shell.run("run", "pause", ID, "--name", "nightly", "--wait", "--poll", "10ms");
        assertThat(paused.exit()).as(paused.err()).isZero();
        assertThat(paused.out())
                .isEqualTo("Run " + ID + " is PAUSED; resume it with pipeforge run resume " + ID + ".\n");
        assertThat(received).anyMatch(r -> r.equals("POST /api/v1/runs/" + ID + "/pause {\"name\":\"nightly\"}"));

        // the savepoint failed: the run reads on
        routes.put("GET /api/v1/runs/" + ID, () -> "{\"run\": " + run("RUNNING", "") + ", \"transitions\": []}");
        Shell.Result failed = shell.run("run", "pause", ID, "--wait", "--poll", "10ms");
        assertThat(failed.exit()).isEqualTo(ExitCode.ERROR.code());
        assertThat(failed.err()).isEqualTo("Run " + ID + " was not paused; it is RUNNING.\n");
        routes.put("GET /api/v1/runs/" + ID, () -> "{\"run\": " + run("PAUSING", "") + ", \"transitions\": []}");
        Shell.Result slow = shell.run("run", "pause", ID, "--wait", "--timeout", "30ms", "--poll", "10ms");
        assertThat(slow.exit()).isEqualTo(ExitCode.TIMEOUT.code());

        routes.put("POST /api/v1/runs/" + ID + "/resume", () -> run("QUEUE", ""));
        Shell.Result resumed = shell.run("run", "resume", ID);
        assertThat(resumed.out()).isEqualTo("Run " + ID + " is QUEUE: it goes on from its savepoint.\n");

        routes.put(
                "POST /api/v1/runs/" + ID + "/savepoint",
                () -> CHECKPOINTS.substring(CHECKPOINTS.indexOf("{\"id\": 7"), CHECKPOINTS.lastIndexOf(']')));
        Shell.Result savepoint = shell.run("run", "savepoint", ID, "--name", "before-upgrade");
        assertThat(savepoint.exit()).as(savepoint.err()).isZero();
        assertThat(savepoint.out()).isEqualTo("Savepoint 7 of run " + ID + " at run/sp-7/ (1.5 KiB).\n");

        routes.put("POST /api/v1/runs/" + ID + "/retry", () -> run("QUEUE", ""));
        assertThat(shell.run("run", "retry", ID, "--from-scratch").out())
                .isEqualTo("Run " + ID + " is QUEUE: its next attempt starts.\n");
        shell.run("run", "retry", ID, "--from-checkpoint");
        shell.run("run", "retry", ID);
        assertThat(received)
                .filteredOn(r -> r.startsWith("POST /api/v1/runs/" + ID + "/retry"))
                .containsExactly(
                        "POST /api/v1/runs/" + ID + "/retry {\"from\":\"SCRATCH\"}",
                        "POST /api/v1/runs/" + ID + "/retry {\"from\":\"CHECKPOINT\"}",
                        "POST /api/v1/runs/" + ID + "/retry {}");
        assertThat(shell.run("run", "retry", ID, "--from-scratch", "--from-checkpoint")
                        .exit())
                .isEqualTo(ExitCode.USAGE.code());

        // a run that is not running cannot be paused
        statuses.put("POST /api/v1/runs/" + ID + "/pause", 400);
        routes.put(
                "POST /api/v1/runs/" + ID + "/pause",
                () -> "{\"code\":\"PF-CONF-0007\",\"message\":\"run is SUCCESS; only a running run can be paused\"}");
        Shell.Result refused = shell.run("run", "pause", ID);
        assertThat(refused.exit()).isEqualTo(ExitCode.INVALID.code());
        assertThat(refused.err()).isEqualTo("ERROR PF-CONF-0007 run is SUCCESS; only a running run can be paused\n");
    }

    @Test
    void explainsAJobOnTheServer() throws Exception {
        routes.put("POST /api/v1/explain", () -> "{\"plan\": {\"nodes\": []}, \"text\": \"Job users  mode=batch\"}");
        Shell.Result text = shell.run(
                "job", "explain", "jobs/users.yaml", "--var", "day=1", "--scheduled-time", "2026-11-07T00:00:00Z");
        assertThat(text.exit()).as(text.err()).isZero();
        assertThat(text.out()).isEqualTo("Job users  mode=batch\n");
        assertThat(received.get(0))
                .startsWith("POST /api/v1/explain {\"definition\":\"version: 1\\nname: users\\n")
                .endsWith("\"variables\":{\"day\":\"1\"},\"scheduledTime\":\"2026-11-07T00:00:00Z\"}");
        Shell.Result json = shell.run("job", "explain", "jobs/users.yaml", "-o", "json");
        assertThat(JSON.readTree(json.out()).path("plan").path("nodes").isArray())
                .isTrue();

        statuses.put("POST /api/v1/explain", 422);
        routes.put(
                "POST /api/v1/explain",
                () -> "{\"code\": \"PF-PLUG-0001\", \"message\": \"The sink plugin 'console' is not installed\"}");
        Shell.Result invalid = shell.run("job", "explain", "jobs/users.yaml");
        assertThat(invalid.exit()).isEqualTo(ExitCode.INVALID.code());
        assertThat(invalid.err()).isEqualTo("ERROR PF-PLUG-0001 The sink plugin 'console' is not installed\n");
    }
}
