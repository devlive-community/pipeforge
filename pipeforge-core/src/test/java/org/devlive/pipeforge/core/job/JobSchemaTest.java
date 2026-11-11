/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.core.TestComponents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The schema is checked with a JSON Schema validator against the jobs the parser accepts and rejects. */
class JobSchemaTest {

    private static final Map<String, Object> SCHEMA = JobSchema.build(TestComponents.rules());
    private static final Schema VALIDATOR = validator(SCHEMA);

    private static Schema validator(Map<String, Object> schema) {
        try {
            String json = new ObjectMapper().writeValueAsString(schema);
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(json, InputFormat.JSON);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> problems(String yaml) {
        return VALIDATOR.validate(yaml, InputFormat.YAML).stream()
                .map(Error::toString)
                .toList();
    }

    private static String job(String rest) {
        return "version: 1\nname: x\n" + rest;
    }

    static Stream<String> validJobs() throws IOException {
        Stream<String> golden = Stream.of("linear", "merge").map(name -> {
            try {
                return Files.readString(
                        Path.of("src/test/resources/explain/" + name + ".yaml"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return Stream.concat(golden, DocsExamplesTest.examples().map(e -> e.substring(e.indexOf('\n') + 1)));
    }

    @ParameterizedTest
    @MethodSource("validJobs")
    void acceptsEveryDocumentedAndTestedJob(String yaml) {
        assertThat(problems(yaml)).as(yaml).isEmpty();
    }

    @Test
    void acceptsVariablesWhereTheParserResolvesThem() {
        assertThat(problems("version: 1\nname: d-${var:x}\nenv: {parallelism: \"${var:p}\", mode: \"${var:m}\"}\n"
                        + "source: [{id: s, type: fake, rows: \"${var:r}\"}]\nsink: [{id: k, type: console}]"))
                .isEmpty();
    }

    @Test
    void rejectsWhatTheParserRejects() {
        String nodes = "source: [{id: s, type: fake}]\nsink: [{id: k, type: console}]";
        assertThat(problems(job(nodes + "\nextra: 1"))).anyMatch(p -> p.contains("extra"));
        assertThat(problems("version: 2\nname: x\n" + nodes)).anyMatch(p -> p.contains("version"));
        assertThat(problems(job("source: [{type: fake}]\nsink: [{id: k, type: console}]")))
                .anyMatch(p -> p.contains("id"));
        assertThat(problems(job("source: [{id: 9s, type: Fake}]\nsink: [{id: k, type: console}]")))
                .isNotEmpty();
        assertThat(problems(job("env: {paralelism: 2}\n" + nodes))).anyMatch(p -> p.contains("paralelism"));
        assertThat(problems(job("env: {mode: fast}\n" + nodes))).anyMatch(p -> p.contains("mode"));
        assertThat(problems(job("source: [{id: s, type: fake, rows: many}]\nsink: [{id: k, type: console}]")))
                .anyMatch(p -> p.contains("rows"));
        assertThat(problems("version: 1\nsource: [{id: s, type: fake}]")).anyMatch(p -> p.contains("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mirrorsTheFieldsOfTheParser() {
        Map<String, Object> properties = (Map<String, Object>) SCHEMA.get("properties");
        assertThat(properties.keySet()).containsExactlyElementsOf(JobParser.FIELDS);
        Map<String, Map<String, Object>> defs = (Map<String, Map<String, Object>>) SCHEMA.get("$defs");
        Map<String, Object> env = (Map<String, Object>) defs.get("env").get("properties");
        assertThat(env).containsKeys("mode", "parallelism", "delivery", "timezone", "variables", "source", "sink");
        assertThat(env.get("delivery").toString())
                .contains("enum=[at-least-once, exactly-once]", "examples=[at-least-once, exactly-once]")
                .contains("default=at-least-once");
        assertThat(env.get("source").toString()).contains("fetch-size", "rows");
        Map<String, Object> sink = (Map<String, Object>) defs.get("sinkNode").get("properties");
        assertThat(sink.keySet()).containsExactlyElementsOf(JobParser.NODE_FIELDS);
        Map<String, Object> source =
                (Map<String, Object>) defs.get("sourceNode").get("properties");
        assertThat(source).doesNotContainKey("inputs");
        assertThat(source.get("type").toString()).contains("examples=[fake]");
        assertThat(defs.get("sourceNode").get("allOf").toString()).contains("const=fake", "fetch-size", "password");
        assertThat(JobSchema.build(Map.of()).get("$defs").toString()).doesNotContain("allOf");
    }

    @Test
    void describesOptions() {
        assertThat(JobSchema.option(Options.key("t").durationType().defaultValue(Duration.ofSeconds(30)))
                        .toString())
                .contains("default=30s", "pattern=^\\d+\\s*(ms|s|m|h|d)$");
        assertThat(JobSchema.option(Options.key("t").durationType().defaultValue(Duration.ofMillis(1500))))
                .containsEntry("default", "1500ms");
        assertThat(JobSchema.option(
                        Options.key("p").stringType().defaultValue("x").withSensitive(true)))
                .containsEntry("writeOnly", true)
                .containsEntry("type", "string")
                .doesNotContainKey("default");
        assertThat(JobSchema.option(Options.key("n").intType().defaultValue(1).withRange(0, 10))
                        .get("anyOf"))
                .isEqualTo(List.of(
                        Map.of("type", "integer", "minimum", 0L, "maximum", 10L),
                        Map.of("type", "string", "pattern", "\\$\\{")));
        assertThat(JobSchema.option(Options.key("r").doubleType().noDefault().withRange(0.5, null))
                        .toString())
                .contains("type=number", "minimum=0.5");
        assertThat(JobSchema.option(Options.key("l").listType().noDefault()).toString())
                .contains("type=[array, string]");
        assertThat(JobSchema.option(Options.key("b").booleanType().defaultValue(true)))
                .containsEntry("default", true);
        assertThat(JobSchema.option(Options.key("m").mapType().noDefault()).toString())
                .contains("type=object");
        assertThat(JobSchema.option(Options.key("o").objectListType().noDefault())
                        .toString())
                .contains("type=array", "items={", "type=object", "additionalProperties");
        assertThat(JobSchema.option(Options.key("d").enumType(Delivery.class).defaultValue(Delivery.EXACTLY_ONCE)))
                .containsEntry("default", "exactly-once")
                .containsEntry("examples", List.of("at-least-once", "exactly-once"));
    }
}
