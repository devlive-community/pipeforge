/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.devlive.pipeforge.api.ApiVersion;
import org.devlive.pipeforge.connector.blackhole.BlackholeSinkFactory;
import org.devlive.pipeforge.connector.fake.FakeSourceFactory;
import org.devlive.pipeforge.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The run endpoints against a master and a worker in this process. */
@SpringBootTest
@AutoConfigureMockMvc
class RunApiTest {

    @TestConfiguration
    static class Master {
        @Bean(destroyMethod = "close")
        InProcessMaster inProcessMaster(PluginManager plugins) {
            return new InProcessMaster(plugins);
        }

        @Bean(destroyMethod = "close")
        @Primary
        MasterClient testMaster(InProcessMaster master) {
            return master.client();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    InProcessMaster master;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        Path root;
        try {
            root = Files.createTempDirectory("pipeforge-run-test");
            Path lib = Files.createDirectories(root.resolve("plugins/run-1.0.0/lib"));
            try (OutputStream out = Files.newOutputStream(lib.resolve("run.jar"));
                    JarOutputStream jar = new JarOutputStream(out, new Manifest())) {
                jar.flush();
            }
            Files.writeString(
                    lib.getParent().resolve("plugin.yaml"),
                    """
                    id: run
                    name: Run
                    version: 1.0.0
                    api-version: "%s"
                    provides:
                      - { type: source, identifier: fake, class: %s }
                      - { type: sink, identifier: blackhole, class: %s }
                      - { type: sink, identifier: down, class: %s }
                    """
                            .formatted(
                                    ApiVersion.descriptorForm(),
                                    FakeSourceFactory.class.getName(),
                                    BlackholeSinkFactory.class.getName(),
                                    DownSinkFactory.class.getName()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("pipeforge.plugin.paths", () -> root.resolve("plugins").toString());
        registry.add("pipeforge.plugin.cache", () -> root.resolve("cache").toString());
        registry.add("pipeforge.master.poll-interval", () -> "50ms");
        registry.add("pipeforge.master.metrics-interval", () -> "50ms");
    }

    static String job(String name, String source) {
        return job(name, source, "blackhole");
    }

    static String job(String name, String source, String sink) {
        String yaml =
                """
                version: 1
                name: %s
                source:
                  - { id: src, type: fake, %s }
                sink:
                  - { id: out, type: %s }
                """
                        .formatted(name, source, sink);
        return "{\"definition\": \""
                + yaml.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
    }

    String submit(String body) throws Exception {
        String response = mvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUE"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    String detail(String id) throws Exception {
        return mvc.perform(get("/api/v1/runs/" + id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    String await(String id, String state) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (true) {
            String detail = detail(id);
            if (state.equals(JsonPath.read(detail, "$.run.status"))) {
                return detail;
            }
            assertThat(System.nanoTime())
                    .as("run %s reaches %s: %s", id, state, detail)
                    .isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void submitsARunAndFollowsItToSuccess() throws Exception {
        String id = submit(job("orders", "rows: 300, rows-per-second: 600"));
        String detail = await(id, "SUCCESS");
        assertThat((String) JsonPath.read(detail, "$.run.job")).isEqualTo("orders");
        assertThat((String) JsonPath.read(detail, "$.definition")).contains("name: orders");
        assertThat((String) JsonPath.read(detail, "$.run.startedAt")).isNotNull();
        assertThat((String) JsonPath.read(detail, "$.run.endedAt")).isNotNull();
        assertThat((Object) JsonPath.read(detail, "$.run.error")).isNull();
        List<String> states = JsonPath.read(detail, "$.transitions[*].to");
        assertThat(states).startsWith("QUEUE").endsWith("SUCCESS").doesNotHaveDuplicates();
        assertThat((String) JsonPath.read(detail, "$.transitions[0].reason")).isEqualTo("submitted");
    }

    @Test
    void anInvalidJobIsRefusedWithoutARun() throws Exception {
        mvc.perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(job("broken", "rows: -1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PF-CONF-0002"));
        mvc.perform(get("/api/v1/runs").param("job", "broken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").isEmpty());
        mvc.perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PF-CONF-0007"));
    }

    @Test
    void cancelsARunThatHasNotEnded() throws Exception {
        String id = submit(job("endless", "unbounded: true, rows-per-second: 100"));
        await(id, "RUNNING");
        mvc.perform(post("/api/v1/runs/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"no longer needed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLING"));
        String detail = await(id, "CANCELLED");
        assertThat((List<String>) JsonPath.read(detail, "$.transitions[*].reason"))
                .contains("no longer needed");
        // an ended run is returned as it is
        mvc.perform(post("/api/v1/runs/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void aCancelledRunIsRetriedByHandAsItsNextAttempt() throws Exception {
        String id = submit(job("retried", "unbounded: true, rows-per-second: 100"));
        await(id, "RUNNING");
        mvc.perform(post("/api/v1/runs/" + id + "/retry"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PF-CONF-0007"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("is RUNNING")));
        mvc.perform(post("/api/v1/runs/" + id + "/cancel")).andExpect(status().isOk());
        await(id, "CANCELLED");

        mvc.perform(post("/api/v1/runs/" + id + "/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": \"CHECKPOINT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no completed checkpoint")));
        mvc.perform(post("/api/v1/runs/" + id + "/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": \"SCRATCH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUE"))
                .andExpect(jsonPath("$.attempt").value(2));
        String detail = await(id, "RUNNING");
        assertThat((List<Integer>) JsonPath.read(detail, "$.attempts[*].attempt"))
                .containsExactly(1, 2);
        assertThat((List<String>) JsonPath.read(detail, "$.attempts[*].status"))
                .containsExactly("CANCELLED", "RUNNING");
        assertThat((List<String>) JsonPath.read(detail, "$.transitions[*].reason"))
                .contains("retried by hand: attempt 2 from the start");
        assertThat((String) JsonPath.read(detail, "$.run.endedAt")).isNull();

        mvc.perform(post("/api/v1/runs/" + id + "/cancel")).andExpect(status().isOk());
        await(id, "CANCELLED");
        mvc.perform(post("/api/v1/runs/nope/retry")).andExpect(status().isNotFound());
    }

    @Test
    void aRunThatFailsKeepsItsError() throws Exception {
        String id = submit(job("down", "rows: 50", "down"));
        String detail = await(id, "FAILED");
        assertThat((String) JsonPath.read(detail, "$.run.error.code")).startsWith("PF-");
        assertThat((String) JsonPath.read(detail, "$.run.error.message")).contains("the target is down");
        assertThat((String) JsonPath.read(detail, "$.run.endedAt")).isNotNull();
    }

    @Test
    void listsRunsNewestFirstByPageStateAndJob() throws Exception {
        String first = submit(job("listed", "rows: 10"));
        await(first, "SUCCESS");
        String second = submit(job("listed", "rows: 10"));
        await(second, "SUCCESS");
        String page = mvc.perform(get("/api/v1/runs").param("job", "listed").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0].id").value(second))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String next = JsonPath.read(page, "$.next");
        mvc.perform(get("/api/v1/runs")
                        .param("job", "listed")
                        .param("limit", "1")
                        .param("page", next))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0].id").value(first))
                .andExpect(jsonPath("$.next").doesNotExist());
        mvc.perform(get("/api/v1/runs").param("job", "listed").param("status", "RUNNING", "QUEUE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").isEmpty());
        mvc.perform(get("/api/v1/runs").param("page", "not-a-page"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PF-CONF-0007"));
        mvc.perform(get("/api/v1/runs/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PF-CONF-0010"));
    }

    @Test
    void aRunTheMasterForgotFailsAsLost() throws Exception {
        String id = submit(job("forgotten", "unbounded: true, rows-per-second: 100"));
        await(id, "RUNNING");
        master.restart();
        String detail = await(id, "FAILED");
        assertThat((String) JsonPath.read(detail, "$.run.error.code")).isEqualTo("PF-CLUS-0016");
    }

    String getJson(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void keepsTheMetricsAndAnalysisOfARunAfterTheMasterForgetsIt() throws Exception {
        String id = submit(job("measured", "rows: 400, rows-per-second: 800"));
        await(id, "SUCCESS");
        String metrics = getJson("/api/v1/runs/" + id + "/metrics");
        assertThat((Integer) JsonPath.read(metrics, "$.rowsRead")).isEqualTo(400);
        assertThat((Integer) JsonPath.read(metrics, "$.rowsWritten")).isEqualTo(400);
        assertThat((List<String>) JsonPath.read(metrics, "$.nodes[*].node")).containsExactly("src", "out");
        List<Integer> read = JsonPath.read(getJson("/api/v1/runs/" + id + "/metrics/history"), "$[*].rowsRead");
        assertThat(read)
                .as("samples while it ran and the last one")
                .isNotEmpty()
                .isSorted()
                .endsWith(400);
        assertThat((String) JsonPath.read(getJson("/api/v1/runs/" + id + "/analysis"), "$.schemaVersion"))
                .isNotBlank();
        assertThat((List<String>) JsonPath.read(getJson("/api/v1/runs/" + id + "/splits"), "$[*].state"))
                .isNotEmpty()
                .containsOnly("FINISHED");
        assertThat((List<String>) JsonPath.read(getJson("/api/v1/runs/" + id + "/tasks"), "$[*].state"))
                .isNotEmpty()
                .containsOnly("FINISHED");

        master.restart();
        assertThat((Integer) JsonPath.read(getJson("/api/v1/runs/" + id + "/metrics"), "$.rowsRead"))
                .as("kept by the server")
                .isEqualTo(400);
        assertThat((String) JsonPath.read(getJson("/api/v1/runs/" + id + "/analysis"), "$.schemaVersion"))
                .isNotBlank();
        assertThat(getJson("/api/v1/runs/" + id + "/splits")).isEqualTo("[]");
    }

    @Test
    void readsTaskLogsThroughTheMaster() throws Exception {
        String id = submit(job("logged", "rows: 20"));
        await(id, "SUCCESS");
        String tasks = getJson("/api/v1/runs/" + id + "/tasks");
        String vertex = JsonPath.read(tasks, "$[0].vertex");
        var attempt = new org.devlive.pipeforge.engine.rpc.ExecutionAttemptId(id, vertex, 0, 0);
        java.nio.file.Path file = master.logs.path("logged", attempt);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "2026-11-17 08:00:00.000 INFO  reading\n2026-11-17 08:00:01.000 WARN  slow target\n");

        String chunk = mvc.perform(get("/api/v1/runs/" + id + "/logs")
                        .param("vertex", vertex)
                        .param("subtask", "0")
                        .param("contains", "slow"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(chunk, "$.text"))
                .contains("slow target")
                .doesNotContain("reading");
        assertThat((Integer) JsonPath.read(chunk, "$.offset")).isPositive();
        mvc.perform(get("/api/v1/runs/" + id + "/logs")
                        .param("vertex", "nothing")
                        .param("subtask", "0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PF-CLUS-0012"));
    }

    @Test
    void showsTheMasterAndItsWorkersWithTheirSlots() throws Exception {
        String id = master.manager
                .register(new org.devlive.pipeforge.engine.master.ResourceManager.WorkerSpec(
                        "0.1.0",
                        "w1:6124",
                        "w1:6125",
                        new org.devlive.pipeforge.engine.master.ResourceManager.Resources(8, 4L << 30, 0, 0),
                        4,
                        java.util.Map.of("zone", "a"),
                        "default",
                        new byte[32],
                        null))
                .workerId();
        master.manager.reserve(
                new org.devlive.pipeforge.engine.master.ResourceManager.FreeSlot(id, "w1:6124", "w1:6125", 0),
                "orders",
                "alloc-1");
        String cluster = getJson("/api/v1/cluster");
        assertThat((String) JsonPath.read(cluster, "$.master.address")).isEqualTo("in-process");
        assertThat((String) JsonPath.read(cluster, "$.master.protocol")).isEqualTo("1.0");
        assertThat((String) JsonPath.read(cluster, "$.workers[0].labels.zone")).isEqualTo("a");
        assertThat((Integer) JsonPath.read(cluster, "$.workers[0].cpuCores")).isEqualTo(8);
        assertThat((String) JsonPath.read(cluster, "$.workers[0].slots[0].job")).isEqualTo("orders");
        assertThat((Integer) JsonPath.read(cluster, "$.slots")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(cluster, "$.slotsUsed")).isEqualTo(1);
        assertThat((Double) JsonPath.read(cluster, "$.usage")).isEqualTo(0.25);
        assertThat((Boolean) JsonPath.read(cluster, "$.workers[0].draining")).isFalse();

        // a drained worker shows it and is offered to no run, until it is uncordoned
        mvc.perform(post("/api/v1/cluster/workers/" + id + "/drain")).andExpect(status().isNoContent());
        assertThat((Boolean) JsonPath.read(getJson("/api/v1/cluster"), "$.workers[0].draining"))
                .isTrue();
        assertThat(master.manager.freeSlots(java.util.Map.of())).isEmpty();
        mvc.perform(post("/api/v1/cluster/workers/" + id + "/uncordon")).andExpect(status().isNoContent());
        assertThat((Boolean) JsonPath.read(getJson("/api/v1/cluster"), "$.workers[0].draining"))
                .isFalse();
        assertThat(master.manager.freeSlots(java.util.Map.of())).hasSize(3);
        mvc.perform(post("/api/v1/cluster/workers/nobody/drain"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PF-CLUS-0020"));
        // a master that elects no leader cannot hand over
        mvc.perform(post("/api/v1/cluster/failover"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PF-CLUS-0019"));
    }

    String preview(String yaml, String extra, int status) throws Exception {
        String body = "{\"definition\": \""
                + yaml.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" + extra + "}";
        return mvc.perform(post("/api/v1/jobs/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(status))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    static final String DOWN =
            """
            version: 1
            name: preview
            source:
              - id: src
                type: fake
                table: shop.orders
                columns:
                  - id INT64 NOT NULL
                  - amount DECIMAL(10, 2)
                %s
            sink:
              - { id: out, type: down }
            """;

    @Test
    void aDryRunShowsWhatTheSinkWouldReceiveAndWritesNothing() throws Exception {
        String result = preview(DOWN.formatted("rows: 30"), "", 200);
        assertThat((String) JsonPath.read(result, "$.outcome"))
                .as("the down sink never ran")
                .isEqualTo("COMPLETE");
        assertThat((String) JsonPath.read(result, "$.node")).isEqualTo("src");
        assertThat((String) JsonPath.read(result, "$.tables[0].table")).isEqualTo("shop.orders");
        assertThat((List<String>) JsonPath.read(result, "$.tables[0].columns[*].name"))
                .containsExactly("id", "amount");
        assertThat((List<Object>) JsonPath.read(result, "$.tables[0].rows")).hasSize(30);
        assertThat((Object) JsonPath.read(result, "$.tables[0].rows[0][1]"))
                .as("decimals as text")
                .isInstanceOf(String.class);
        assertThat((Integer) JsonPath.read(result, "$.rowsRead")).isEqualTo(30);
        assertThat((Object) JsonPath.read(result, "$.error")).isNull();
    }

    @Test
    void aPreviewStopsAtTheLimitOrTheTimeout() throws Exception {
        String limited = preview(DOWN.formatted("unbounded: true"), ", \"limit\": 10", 200);
        assertThat((String) JsonPath.read(limited, "$.outcome")).isEqualTo("LIMIT");
        assertThat((List<Object>) JsonPath.read(limited, "$.tables[0].rows")).hasSize(10);

        String slow = preview(
                DOWN.formatted("unbounded: true\n    rows-per-second: 2"),
                ", \"limit\": 100, \"timeoutSeconds\": 1",
                200);
        assertThat((String) JsonPath.read(slow, "$.outcome")).isEqualTo("TIMEOUT");
        assertThat((List<Object>) JsonPath.read(slow, "$.tables[0].rows")).hasSizeLessThan(100);
    }

    @Test
    void previewsANodeAndRefusesUnknownNodesAndInvalidJobs() throws Exception {
        String node = preview(DOWN.formatted("rows: 5"), ", \"node\": \"src\", \"limit\": 3", 200);
        assertThat((String) JsonPath.read(node, "$.node")).isEqualTo("src");
        assertThat((List<Object>) JsonPath.read(node, "$.tables[0].rows")).hasSize(3);
        assertThat((String) JsonPath.read(preview(DOWN.formatted("rows: 5"), ", \"node\": \"out\"", 200), "$.node"))
                .as("a sink shows its input")
                .isEqualTo("src");
        assertThat((String) JsonPath.read(preview(DOWN.formatted("rows: 5"), ", \"node\": \"nothing\"", 400), "$.code"))
                .isEqualTo("PF-CONF-0007");
        assertThat((String) JsonPath.read(preview(DOWN.formatted("rows: -1"), "", 422), "$.code"))
                .isEqualTo("PF-CONF-0002");
    }
}
