/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Every endpoint of the API through the full Spring MVC stack, against an installed plugin. */
@SpringBootTest
@AutoConfigureMockMvc
class ServerApiTest {

    static final String JOB =
            """
            version: 1
            name: demo
            env:
              variables: { day: "${date:yyyy-MM-dd,-1d}" }
              sink.batch-size: 10
            source:
              - { id: src, type: demo-source, path: "/data/${var:day}", password: s3cret }
            sink:
              - { id: out, type: demo-sink }
            """;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void plugins(DynamicPropertyRegistry registry) {
        Path root;
        try {
            root = Files.createTempDirectory("pipeforge-server-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path plugins = TestPlugin.install(root);
        registry.add("pipeforge.plugin.paths", plugins::toString);
        registry.add("pipeforge.plugin.cache", () -> root.resolve("cache").toString());
        registry.add("pipeforge.job.timezone", () -> "Asia/Shanghai");
    }

    private ResultActions postJson(String path, String body, String language) throws Exception {
        return mvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept-Language", language)
                .content(body));
    }

    private static String job(String yaml, String extra) {
        return "{\"definition\": " + quote(yaml) + extra + "}";
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // ------------------------------------------------------------------ system, plugins, catalogs

    @Test
    void reportsTheVersions() throws Exception {
        mvc.perform(get("/api/v1/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isString())
                .andExpect(jsonPath("$.connectorApi").isString())
                .andExpect(jsonPath("$.java", startsWith("21")));
    }

    @Test
    void listsInstalledAndRejectedPlugins() throws Exception {
        mvc.perform(get("/api/v1/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins", hasSize(1)))
                .andExpect(jsonPath("$.plugins[0].id").value("demo"))
                .andExpect(jsonPath("$.plugins[0].components[0].type").value("source"))
                .andExpect(jsonPath("$.plugins[0].components[1].identifier").value("demo-sink"))
                .andExpect(jsonPath("$.plugins[0].datasource.type").value("demo-db"))
                .andExpect(jsonPath("$.unavailable[0].location", containsString("broken-1.0.0")))
                .andExpect(jsonPath(
                        "$.unavailable[0].reason", containsString("provides must list at least one component")));
    }

    @Test
    void describesTheOptionsOfAComponent() throws Exception {
        mvc.perform(get("/api/v1/components/source/demo-source").param("plugin", "demo@1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugin").value("demo@1.0.0"))
                .andExpect(jsonPath("$.options[0].key").value("query"))
                .andExpect(jsonPath("$.options[0].requirement").value("one-of"))
                .andExpect(
                        jsonPath("$.options[?(@.key == 'rows')].defaultValue").value(10))
                .andExpect(jsonPath("$.options[?(@.key == 'rows')].description").value("Rows to produce"))
                .andExpect(jsonPath("$.options[?(@.key == 'password')].defaultValue")
                        .value(hasItem(nullValue())))
                .andExpect(
                        jsonPath("$.options[?(@.key == 'password')].sensitive").value(true))
                .andExpect(jsonPath("$.options[?(@.key == 'interval')].defaultValue")
                        .value("30s"))
                .andExpect(jsonPath("$.options[?(@.key == 'mode')].values[0]").value(hasItem("FAST")))
                .andExpect(jsonPath("$.options[?(@.key == 'key')].requirement").value("conditional"))
                .andExpect(jsonPath("$.exclusive[0][1]").value("path"))
                .andExpect(jsonPath("$.conditions[0].option").value("mode"))
                .andExpect(jsonPath("$.conditions[0].value").value("SAFE"))
                .andExpect(jsonPath("$.nativeNamespaces[0]").value("demo.properties"));
        mvc.perform(get("/api/v1/components/sink/demo-sink"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options", hasSize(0)));
    }

    @Test
    void unknownComponentsAreErrors() throws Exception {
        mvc.perform(get("/api/v1/components/catalog/demo-source"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PF-CONF-0007"))
                .andExpect(jsonPath("$.params.reason", containsString("source, transform or sink")));
        mvc.perform(get("/api/v1/components/source/kafka").header("Accept-Language", "zh-CN,zh;q=0.9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PF-PLUG-0001"))
                .andExpect(jsonPath("$.message", containsString("kafka")))
                .andExpect(jsonPath("$.hint", containsString("插件")));
    }

    @Test
    void listsFunctionsInTheRequestedLanguage() throws Exception {
        mvc.perform(get("/api/v1/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'COALESCE')].description")
                        .value(hasItem(startsWith("Returns the first argument"))))
                .andExpect(jsonPath("$[?(@.name == 'RAND')].deterministic").value(hasItem(false)));
        mvc.perform(get("/api/v1/functions").header("Accept-Language", "zh-CN"))
                .andExpect(jsonPath("$[?(@.name == 'COALESCE')].description").value(hasItem(startsWith("返回第一个"))))
                .andExpect(jsonPath("$[?(@.name == 'COALESCE')].examples[0].result")
                        .isNotEmpty());
    }

    @Test
    void listsErrorCodes() throws Exception {
        mvc.perform(get("/api/v1/errors").header("Accept-Language", "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("PF-CONF-0001"))
                .andExpect(jsonPath("$[?(@.code == 'PF-CONF-0007')].message").value(hasItem("请求不合法：{reason}")))
                .andExpect(jsonPath("$[?(@.code == 'PF-CONF-0007')].category").value(hasItem("CONF")))
                .andExpect(jsonPath("$[?(@.code == 'PF-CONF-0007')].params[0]").value(hasItem("reason")))
                .andExpect(jsonPath("$[?(@.code == 'PF-CONF-0007')].kind").value(hasItem("FATAL")));
    }

    // ------------------------------------------------------------------ jobs

    @Test
    void validatesJobs() throws Exception {
        postJson("/api/v1/jobs/validate", job(JOB, ""), "en-US")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(
                        jsonPath("$.warnings[0]").value("env.sink.batch-size is not an option of any sink in the job"));
        postJson("/api/v1/jobs/validate", job(JOB.replace("  - { id: out", "  - { id: src"), ""), "zh-CN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].code").value("PF-CONF-0006"))
                .andExpect(jsonPath("$.errors[0].scope.line").value(9))
                .andExpect(jsonPath("$.errors[0].scope.column").value(5))
                .andExpect(jsonPath("$.errors[0].scope.path").value("sink[0].id"))
                .andExpect(jsonPath("$.errors[0].message", startsWith("作业定义在 line 9")));
        postJson("/api/v1/jobs/validate", job(JOB.replace("type: demo-sink", "type: kafka"), ""), "en-US")
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].code").value("PF-PLUG-0001"));
    }

    @Test
    void explainsJobsWithVariablesAndTheScheduledTime() throws Exception {
        postJson(
                        "/api/v1/explain",
                        job(JOB, ", \"variables\": {\"region\": \"cn\"}, \"scheduledTime\": \"2026-11-07T00:30:00Z\""),
                        "en-US")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.job.name").value("demo"))
                .andExpect(jsonPath("$.plan.job.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.plan.variables.day").value("2026-11-06"))
                .andExpect(jsonPath("$.plan.variables.region").value("cn"))
                .andExpect(jsonPath("$.plan.nodes[0].plugin").value("demo@1.0.0"))
                .andExpect(jsonPath("$.plan.nodes[0].options[?(@.key == 'path')].value")
                        .value(hasItem("/data/2026-11-06")))
                .andExpect(jsonPath("$.plan.nodes[0].options[?(@.key == 'password')].value")
                        .value(hasItem("******")))
                .andExpect(jsonPath("$.plan.nodes[0].outputTables[0].columns[0].type")
                        .value("INT64"))
                .andExpect(jsonPath("$.plan.nodes[0].outputTables[0].columns[0].nullable")
                        .value(false))
                .andExpect(jsonPath("$.text", startsWith("Job demo  mode=batch (derived)")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("s3cret"))));
    }

    @Test
    void invalidJobsAreErrorResponsesWhenExplained() throws Exception {
        postJson("/api/v1/explain", job(JOB.replace("${var:day}", "${var:nope}"), ""), "en-US")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PF-CONF-0004"))
                .andExpect(jsonPath("$.message").value("Variable '${var:nope}' is not defined"));
        postJson("/api/v1/explain", "{\"definition\": \"  \"}", "en-US")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PF-CONF-0007"));
        postJson("/api/v1/jobs/validate", "{}", "en-US")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.reason", containsString("definition")));
    }

    // ------------------------------------------------------------------ expressions

    private static final String ROW =
            "\"columns\": [{\"name\": \"amount\", \"type\": \"DECIMAL(10, 2)\", \"value\": \"12.50\"},"
                    + " {\"name\": \"name\", \"type\": \"STRING\", \"value\": null}]";

    @Test
    void evaluatesExpressions() throws Exception {
        postJson("/api/v1/expressions/evaluate", "{\"expression\": \"amount * 2\", " + ROW + "}", "en-US")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DECIMAL(21, 2)"))
                .andExpect(jsonPath("$.value").value("25.00"))
                .andExpect(jsonPath("$.deterministic").value(true));
        postJson("/api/v1/expressions/evaluate", "{\"expression\": \"COALESCE(name, 'n/a')\", " + ROW + "}", "en-US")
                .andExpect(jsonPath("$.value").value("n/a"));
        postJson(
                        "/api/v1/expressions/evaluate",
                        "{\"expression\": \"name IS NULL AND RAND() < 2\", " + ROW + "}",
                        "en-US")
                .andExpect(jsonPath("$.type").value("BOOLEAN"))
                .andExpect(jsonPath("$.value").value("TRUE"))
                .andExpect(jsonPath("$.deterministic").value(false));
        postJson("/api/v1/expressions/evaluate", "{\"expression\": \"CAST(NULL AS INT)\"}", "en-US")
                .andExpect(jsonPath("$.value").value(nullValue()));
        postJson(
                        "/api/v1/expressions/evaluate",
                        "{\"expression\": \"HOUR(TIMESTAMP_LTZ '2026-11-07 00:30:00Z')\", \"timezone\": \"Asia/Shanghai\"}",
                        "en-US")
                .andExpect(jsonPath("$.value").value("8"));
    }

    @Test
    void expressionProblemsHaveTheirCodes() throws Exception {
        postJson("/api/v1/expressions/evaluate", "{\"expression\": \"amont * 2\", " + ROW + "}", "en-US")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PF-PLAN-0007"))
                .andExpect(jsonPath("$.params.reason", containsString("did you mean 'amount'?")));
        postJson("/api/v1/expressions/evaluate", "{\"expression\": \"amount / 0\", " + ROW + "}", "en-US")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PF-DATA-0006"));
        postJson("/api/v1/expressions/evaluate", "{\"expression\": \" \"}", "en-US")
                .andExpect(status().isBadRequest());
        postJson(
                        "/api/v1/expressions/evaluate",
                        "{\"expression\": \"1\", \"columns\": [{\"name\": \"a\", \"type\": \"NUMBER\", \"value\": \"1\"}]}",
                        "en-US")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.reason").value("column 'a' has the unknown type 'NUMBER'"));
        postJson(
                        "/api/v1/expressions/evaluate",
                        "{\"expression\": \"1\", \"columns\": [{\"name\": \"a\", \"type\": \"INT32\", \"value\": \"x\"}]}",
                        "en-US")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.reason", startsWith("the value of column 'a' is not a INT32")));
        postJson(
                        "/api/v1/expressions/evaluate",
                        "{\"expression\": \"1\", \"columns\": [{\"type\": \"INT32\"}]}",
                        "en-US")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.reason").value("column 1 needs a name and a type"));
        postJson("/api/v1/expressions/evaluate", "{\"expression\": \"1\", \"timezone\": \"Mars/Base\"}", "en-US")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.reason").value("unknown time zone 'Mars/Base'"));
    }

    // ------------------------------------------------------------------ protocol errors and the UI

    @Test
    void protocolErrorsAreErrorObjects() throws Exception {
        postJson("/api/v1/explain", "{not json", "en-US")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PF-CONF-0007"));
        mvc.perform(put("/api/v1/explain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.params.reason").value("the endpoint does not support PUT"));
        mvc.perform(post("/api/v1/explain").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/v1/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PF-CONF-0007"))
                .andExpect(jsonPath("$.params.reason").value("there is no endpoint /api/v1/nothing"));
    }

    @Test
    void servesTheWebUiWithClientSideRoutes() throws Exception {
        mvc.perform(get("/")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("test ui")));
        mvc.perform(get("/jobs/editor"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("test ui")));
        mvc.perform(get("/assets/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("export const app")));
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(get("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PF-CONF-0007"));
    }
}
