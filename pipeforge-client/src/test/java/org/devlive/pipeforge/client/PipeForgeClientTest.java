/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a stub of the server: requests, answers and errors. */
class PipeForgeClientTest {

    record Answer(int status, String body) {}

    /** What the stub received: method, path with query, language, body. */
    record Received(String method, String uri, String language, String body) {}

    HttpServer server;
    final Map<String, Function<Received, Answer>> routes = new ConcurrentHashMap<>();
    final List<Received> received = new CopyOnWriteArrayList<>();
    PipeForgeClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = PipeForgeClient.builder(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                .language("zh-CN")
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange exchange) throws IOException {
        Received request = new Received(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath()
                        + (exchange.getRequestURI().getRawQuery() == null
                                ? ""
                                : "?" + exchange.getRequestURI().getRawQuery()),
                String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Language")),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        received.add(request);
        Function<Received, Answer> route =
                routes.get(request.method() + " " + exchange.getRequestURI().getPath());
        Answer answer = route == null
                ? new Answer(404, "{\"code\":\"PF-CONF-0007\",\"message\":\"no route\"}")
                : route.apply(request);
        byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    static final String RUN =
            """
            {"id": "r 1", "job": "orders", "status": "RUNNING", "submittedAt": "2026-11-17T08:00:00Z",
             "startedAt": "2026-11-17T08:00:01Z", "rowsRead": 10, "rowsWritten": null, "addedLater": true}
            """;

    @Test
    void submitsARunWithItsVariablesAndLanguage() {
        routes.put("POST /api/v1/runs", r -> new Answer(201, RUN));
        Run run = client.submitRun("version: 1", Map.of("day", "2026-11-17"), Instant.parse("2026-11-17T00:00:00Z"));
        assertThat(run.id()).isEqualTo("r 1");
        assertThat(run.startedAt()).isEqualTo(Instant.parse("2026-11-17T08:00:01Z"));
        assertThat(run.rowsRead()).isEqualTo(10L);
        assertThat(run.rowsWritten()).as("null stays null").isNull();
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.language()).isEqualTo("zh-CN");
            assertThat(r.body())
                    .isEqualTo("{\"definition\":\"version: 1\",\"variables\":{\"day\":\"2026-11-17\"},"
                            + "\"scheduledTime\":\"2026-11-17T00:00:00Z\"}");
        });
    }

    @Test
    void encodesPathsAndQueries() {
        routes.put("GET /api/v1/runs", r -> new Answer(200, "{\"runs\": [" + RUN + "], \"next\": \"t+2\"}"));
        routes.put("GET /api/v1/runs/r 1/logs", r -> new Answer(200, "{\"text\":\"a\\n\",\"offset\":2,\"eof\":true}"));
        RunPage page = client.listRuns(RunQuery.all()
                .withStatuses("FAILED", "TIMEOUT")
                .withJob("orders daily")
                .withLimit(20)
                .withPage("t+1"));
        assertThat(page.runs()).hasSize(1);
        assertThat(page.next()).isEqualTo("t+2");
        LogChunk chunk = client.readLog(
                "r 1", LogQuery.of("src", 0, 0).withContains("a&b").withLevel("WARN"));
        assertThat(chunk.eof()).isTrue();
        assertThat(received)
                .extracting(Received::uri)
                .containsExactly(
                        "/api/v1/runs?status=FAILED&status=TIMEOUT&job=orders%20daily&limit=20&page=t%2B1",
                        "/api/v1/runs/r%201/logs?vertex=src&subtask=0&attempt=0&offset=0&contains=a%26b&level=WARN");
        assertThatThrownBy(() -> client.uri(Endpoint.GET_RUN, Map.of("id", "x"), Map.of("bogus", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bogus is not a parameter of GET_RUN");
    }

    @Test
    void readsListsAndTheClusterIgnoringFieldsItDoesNotKnow() {
        routes.put(
                "GET /api/v1/runs/r/tasks",
                r -> new Answer(
                        200,
                        """
                [{"vertex": "src", "subtask": 1, "attempt": 0, "state": "RUNNING", "worker": "w1", "x": 1}]
                """));
        routes.put(
                "GET /api/v1/cluster",
                r -> new Answer(
                        200,
                        """
                {"master": {"address": "m:6123", "version": "0.1.0", "protocol": "1.0"}, "slots": 4,
                 "slotsUsed": 1, "usage": 0.25, "workers": [{"id": "w1", "rpcAddress": "w:1", "dataAddress": "w:2",
                 "labels": {"zone": "a"}, "cpuCores": 8, "taskHeapBytes": 1, "managedBytes": 2, "networkBytes": 3,
                 "tasksRunning": 1, "sinceHeartbeatMs": 400, "slots": [{"index": 0, "allocated": true, "job": "o"}]}]}
                """));
        assertThat(client.tasks("r")).singleElement().satisfies(t -> {
            assertThat(t.subtask()).isEqualTo(1);
            assertThat(t.worker()).isEqualTo("w1");
        });
        ClusterView cluster = client.cluster();
        assertThat(cluster.usage()).isEqualTo(0.25);
        assertThat(cluster.workers()).singleElement().satisfies(w -> {
            assertThat(w.labels()).containsEntry("zone", "a");
            assertThat(w.slots()).singleElement().extracting(Slot::job).isEqualTo("o");
        });
    }

    @Test
    void aRefusalCarriesTheErrorOfTheServer() {
        routes.put(
                "GET /api/v1/runs/nope",
                r -> new Answer(
                        404,
                        """
                {"code": "PF-CLUS-0014", "name": "RUN_NOT_FOUND", "message": "There is no run nope",
                 "hint": "List the runs", "params": {"run": "nope", "missing": null}, "scope": {}}
                """));
        assertThatThrownBy(() -> client.run("nope")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.code()).isEqualTo("PF-CLUS-0014");
            assertThat(e.getMessage()).isEqualTo("There is no run nope");
            assertThat(e.error().hint()).isEqualTo("List the runs");
            assertThat(e.error().params()).containsEntry("run", "nope").containsEntry("missing", null);
        });
    }

    @Test
    void anAnswerThatIsNoErrorOfTheServerIsDescribed() {
        routes.put("GET /api/v1/version", r -> new Answer(502, "<html>Bad Gateway</html>"));
        assertThatThrownBy(() -> client.version()).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(502);
            assertThat(e.code()).isEqualTo("PF-SYS-0001");
            assertThat(e.getMessage())
                    .isEqualTo("Internal error: the server answered HTTP 502 without an error description:"
                            + " <html>Bad Gateway</html>");
        });
    }

    @Test
    void anUnreachableServerIsReportedWithItsAddress() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        PipeForgeClient down = PipeForgeClient.builder(URI.create("http://127.0.0.1:" + port))
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        assertThatThrownBy(down::cluster).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isZero();
            assertThat(e.code()).isEqualTo("PF-CLUS-0008");
            assertThat(e.getMessage()).startsWith("The server at http://127.0.0.1:" + port + " cannot be reached: ");
        });
    }

    @Test
    void waitsForARunToEnd() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        routes.put(
                "GET /api/v1/runs/r",
                r -> new Answer(
                        200,
                        "{\"run\": "
                                + RUN.replace("RUNNING", asked.incrementAndGet() < 3 ? "RUNNING" : "SUCCESS")
                                        .replace("r 1", "r")
                                + ", \"transitions\": []}"));
        RunDetail ended = client.awaitRun("r", Duration.ofMillis(10), Duration.ofSeconds(5));
        assertThat(ended.run().status()).isEqualTo("SUCCESS");
        assertThat(asked).hasValue(3);

        routes.put("GET /api/v1/runs/r", r -> new Answer(200, "{\"run\": " + RUN + "}"));
        assertThatThrownBy(() -> client.awaitRun("r", Duration.ofMillis(10), Duration.ofMillis(50)))
                .isInstanceOf(TimeoutException.class)
                .hasMessage("Run r is RUNNING after 0 s");
    }

    @Test
    void followsALogFromItsOffsetUntilItEnds() throws Exception {
        List<String> chunks = List.of(
                "{\"text\":\"one\\n\",\"offset\":4,\"eof\":false}",
                "{\"text\":\"\",\"offset\":4,\"eof\":false}",
                "{\"text\":\"two\\n\",\"offset\":8,\"eof\":true}");
        AtomicInteger read = new AtomicInteger();
        routes.put("GET /api/v1/runs/r/logs", r -> new Answer(200, chunks.get(read.getAndIncrement())));
        List<String> text = new ArrayList<>();
        long offset = client.followLog(
                "r", LogQuery.of("src", 0, 0).withOffset(-100), Duration.ofMillis(5), text::add, c -> true);
        assertThat(offset).isEqualTo(8);
        assertThat(text).containsExactly("one\n", "two\n");
        assertThat(received)
                .extracting(Received::uri)
                .allMatch(u -> u.startsWith("/api/v1/runs/r/logs?vertex=src&subtask=0&attempt=0&offset="))
                .extracting(u -> u.replaceAll(".*offset=(-?\\d+).*", "$1"))
                .containsExactly("-100", "4", "4");
    }

    @Test
    void cancelsWithAReasonAndChecksAndExplainsJobs() {
        routes.put("POST /api/v1/runs/r/cancel", r -> new Answer(200, RUN.replace("RUNNING", "CANCELLING")));
        routes.put(
                "POST /api/v1/jobs/validate",
                r -> new Answer(
                        200,
                        """
                {"valid": false, "errors": [{"code": "PF-PLUG-0001", "message": "not installed",
                 "scope": {"node": "out"}}], "warnings": []}
                """));
        routes.put("POST /api/v1/explain", r -> new Answer(200, "{\"plan\": {\"nodes\": []}, \"text\": \"Job x\"}"));
        assertThat(client.cancelRun("r", "by hand").status()).isEqualTo("CANCELLING");
        ValidationResult validation = client.validate("job", Map.of());
        assertThat(validation.valid()).isFalse();
        assertThat(validation.errors()).singleElement().satisfies(e -> assertThat(e.scope())
                .containsEntry("node", "out"));
        assertThat(client.explain("job", Map.of()).text()).isEqualTo("Job x");
        assertThat(received.get(0).body()).isEqualTo("{\"reason\":\"by hand\"}");
    }
}
