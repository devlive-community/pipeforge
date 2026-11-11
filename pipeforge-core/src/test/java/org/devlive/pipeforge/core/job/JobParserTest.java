/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.Errors;
import org.devlive.pipeforge.core.TestComponents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JobParserTest {

    private static final String JOB =
            """
            version: 1
            name: orders-${var:region}
            description: Copies orders
            env:
              mode: batch
              parallelism: 4
              delivery: exactly-once
              timezone: UTC
              variables:
                biz_date: "${date:yyyy-MM-dd,-1d}"
                region: cn
              source:
                fetch-size: 500
            source:
              - id: src
                type: fake
                rows: 100
                split: { strategy: range, column: id }
                tables: [a, b]
            transform:
              - id: map
                type: rename
                from: name
                to: customer
            sink:
              - id: out
                type: console
                plugin: console@1.0.0
                inputs: [map]
                parallelism: 1
                path: /data/${var:biz_date}/${job:name}
            """;

    private static JobDefinition parse(String yaml) {
        return TestComponents.parse(yaml);
    }

    private static String invalid(String yaml) {
        return Errors.message(ErrorCode.JOB_INVALID, () -> parse(yaml));
    }

    @Test
    void readsTheWholeModel() {
        JobDefinition job = parse(JOB);
        assertThat(job.version()).isEqualTo(1);
        assertThat(job.name()).isEqualTo("orders-cn");
        assertThat(job.description()).isEqualTo("Copies orders");
        assertThat(job.timeZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(job.variables()).containsExactly(Map.entry("biz_date", "2026-11-06"), Map.entry("region", "cn"));
        assertThat(job.env().toMap())
                .containsExactly(
                        Map.entry("mode", "batch"),
                        Map.entry("parallelism", 4L),
                        Map.entry("delivery", "exactly-once"),
                        Map.entry("timezone", "UTC"),
                        Map.entry("source.fetch-size", 500L));
        assertThat(job.nodes()).extracting(NodeDefinition::id).containsExactly("src", "map", "out");

        NodeDefinition src = job.node("src").orElseThrow();
        assertThat(src.kind()).isEqualTo(NodeKind.SOURCE);
        assertThat(src.inputs()).isEmpty();
        assertThat(src.options())
                .containsExactly(
                        Map.entry("rows", 100L),
                        Map.entry("split.strategy", "range"),
                        Map.entry("split.column", "id"),
                        Map.entry("tables", List.of("a", "b")));
        assertThat(src.location()).isEqualTo(new Location(15, 5, "source[0]"));

        NodeDefinition map = job.node("map").orElseThrow();
        assertThat(map.inputs()).containsExactly("src");
        assertThat(map.inputsImplied()).isTrue();

        NodeDefinition out = job.nodes(NodeKind.SINK).get(0);
        assertThat(out.plugin()).isEqualTo("console@1.0.0");
        assertThat(out.inputsImplied()).isFalse();
        assertThat(out.parallelism()).isEqualTo(1);
        assertThat(out.options()).containsExactly(Map.entry("path", "/data/2026-11-06/orders-cn"));
    }

    @Test
    void jsonAndYamlDescribeTheSameJob() {
        String json =
                """
                {
                  "version": 1,
                  "name": "demo",
                  "env": {"parallelism": 2, "source.fetch-size": 10},
                  "source": [{"id": "src", "type": "fake", "split.strategy": "range"}],
                  "sink": [{"id": "out", "type": "console", "inputs": "src"}]
                }
                """;
        String yaml =
                """
                version: 1
                name: demo
                env:
                  parallelism: 2
                  source: { fetch-size: 10 }
                source:
                  id: src
                  type: fake
                  split:
                    strategy: range
                sink:
                  - { id: out, type: console, inputs: src }
                """;
        JobDefinition a = parse(json);
        JobDefinition b = parse(yaml);
        assertThat(a.env().toMap()).isEqualTo(b.env().toMap());
        assertThat(a.nodes())
                .extracting(NodeDefinition::options)
                .isEqualTo(b.nodes().stream().map(NodeDefinition::options).toList());
        assertThat(a.nodes()).extracting(NodeDefinition::inputs).containsExactly(List.of(), List.of("src"));
    }

    @Test
    void omittedInputsReadTheLastSourceOrTransformWrittenBefore() {
        JobDefinition job = parse(
                """
                version: 1
                name: fan-out
                source:
                  - { id: a, type: fake }
                  - { id: b, type: fake }
                sink:
                  - { id: x, type: console }
                  - { id: y, type: console, inputs: [a, b] }
                  - { id: z, type: console }
                """);
        assertThat(job.node("x").orElseThrow().inputs()).containsExactly("b");
        assertThat(job.node("y").orElseThrow().inputs()).containsExactly("a", "b");
        assertThat(job.node("z").orElseThrow().inputs()).containsExactly("b");
    }

    @Test
    void defaultsComeFromTheContext() {
        JobDefinition job = parse(
                """
                version: 1
                name: minimal
                source: [{ id: s, type: fake }]
                sink: [{ id: k, type: console }]
                """);
        assertThat(job.timeZone()).isEqualTo(TestComponents.SHANGHAI);
        assertThat(job.description()).isNull();
        assertThat(job.variables()).isEmpty();
        assertThat(job.env().keys()).isEmpty();
        assertThat(job.nodes(NodeKind.TRANSFORM)).isEmpty();
    }

    @Test
    void variablesWorkInEveryTextIncludingTimeZoneAndName() {
        JobDefinition job = JobParser.parse(
                """
                version: 1
                name: ${var:prefix}-${date:yyyyMMdd}
                env:
                  timezone: ${env:TZ}
                  parallelism: "${var:p}"
                  variables: { prefix: daily, p: 3 }
                source: [{ id: s, type: fake, rows: "${var:p}", note: "$${var:p} stays" }]
                sink: [{ id: k, type: console, parallelism: "${var:p}" }]
                """,
                TestComponents.context()
                        .environment(Map.of("TZ", "America/New_York"))
                        .build());
        assertThat(job.timeZone()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(job.name()).isEqualTo("daily-20261106");
        assertThat(job.env().toMap()).containsEntry("parallelism", "3");
        assertThat(job.node("s").orElseThrow().options())
                .containsEntry("rows", "3")
                .containsEntry("note", "${var:p} stays");
        assertThat(job.node("k").orElseThrow().parallelism()).isEqualTo(3);
    }

    @Test
    void undefinedVariablesAreReportedBeforeAnythingRuns() {
        assertThat(Errors.message(
                        ErrorCode.VARIABLE_UNDEFINED, () -> parse(JOB.replace("${var:biz_date}", "${var:day}"))))
                .isEqualTo("Variable '${var:day}' is not defined");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiterString = " => ",
            quoteCharacter = '"',
            textBlock =
                    """
            "name: orders-${var:region}"           => "name: [a, b]"                   => line 2, column 7 (name): the name must be a non-empty text
            "name: orders-${var:region}"           => "name: 'a b'"                    => invalid job name 'a b'
            "version: 1"                           => "version: 2"                     => line 1, column 10 (version): unsupported version '2'
            "description: Copies orders"           => "descripton: x"                  => unknown field 'descripton'; did you mean 'description'?
            "  timezone: UTC"                      => "  timezone: Mars/Base"          => line 8, column 13 (env.timezone): unknown time zone 'Mars/Base'
            "  timezone: UTC"                      => "  worker.heap: 4g"              => 'worker.heap' is cluster configuration
            "  - id: map"                          => "  - id: src"                    => the node id 'src' is already used at line 15
            "  - id: map"                          => "  - id: 9map"                   => invalid node id '9map'
            "    type: rename"                     => "    type: Rename"               => invalid type 'Rename'
            "    plugin: console@1.0.0"            => "    plugin: console@1"          => invalid plugin 'console@1'
            "    rows: 100"                        => "    inputs: [x]"                => line 17, column 13 (source[0].inputs): sources have no inputs
            "    inputs: [map]"                    => "    inputs: []"                 => inputs must name at least one node
            "    parallelism: 1"                   => "    parallelism: 0"             => parallelism must be between 1 and 32768, got 0
            "    parallelism: 1"                   => "    parallelism: many"          => parallelism must be a whole number, got 'many'
            "    tables: [a, b]"                   => "    split.strategy: hash"       => 'split.strategy' is written twice
            "region: cn"                           => "region: [cn]"                   => the value of variable 'region' must be a text or number
            "    from: name"                       => "    from: !!binary aGk="        => the tag tag:yaml.org,2002:binary is not supported
            "    rows: 100"                        => "    rows: 100\\n    rows: 200"  => line 18, column 5 (source[0].rows): duplicate key 'rows'
            "  - id: out"                          => "  - name: out"                  => line 26, column 5 (sink[0]): the node has no id
            "yyyy-MM-dd,-1d}"                      => "yyyy,-1x}"                      => line 10, column 15 (env.variables.biz_date): invalid date offset '-1x'
            """)
    void structuralProblemsAreReportedWithTheirLocation(String from, String to, String expected) {
        String yaml = JOB.replace(from, to.replace("\\n", "\n"));
        assertThat(yaml).isNotEqualTo(JOB);
        assertThat(invalid(yaml)).contains(expected);
    }

    @Test
    void definitionErrorsCarryTheirLocationInTheScope() {
        PipeForgeException error =
                Errors.thrown(ErrorCode.JOB_INVALID, () -> parse(JOB.replace("    rows: 100", "    inputs: [x]")));
        assertThat(error.scope().toMap())
                .containsExactly(Map.entry("line", 17), Map.entry("column", 13), Map.entry("path", "source[0].inputs"));
    }

    @Test
    void documentLevelProblems() {
        assertThat(invalid("version: 1\nname: [")).startsWith("The job definition is invalid at line 2");
        assertThat(invalid("- a\n- b")).contains("a job is a mapping with the fields version, name");
        assertThat(invalid("")).contains("the job has no version");
        assertThat(invalid("version: 1")).contains("the job has no name");
        assertThat(invalid("version: 1\nname: x\nsink: [{id: k, type: console}]"))
                .contains("(source): a job needs at least one source");
        assertThat(invalid("version: 1\nname: x\nsource: [{id: s, type: fake}]\nsink: []"))
                .contains("a job needs at least one sink");
        assertThat(invalid("version: 1\nname: x\nsource: fake")).contains("source is a list of nodes");
        assertThat(invalid("version: 1\nname: x\nsource: [fake]")).contains("a node is a mapping with id, type");
        assertThat(invalid("version: 1\nname: x\nenv: 4\nsource: [{id: s, type: fake}]"))
                .contains("env is a mapping");
        assertThat(invalid("version: 1\nname: x\nbase: &b {id: s, type: fake}\nsource: [{<<: *b}]"))
                .contains("merge keys (<<) are not supported");
        assertThat(invalid("version: 1\nname: x\nbase: 1\nsource: [{id: s, type: fake}]"))
                .contains("unknown field 'base'");
        assertThat(invalid("version: 1\nname: x\nsource: [{<<: {id: s}, type: fake}]"))
                .contains("merge keys (<<) are not supported");
        assertThat(invalid("version: 1\nname: x\nsource: [{[a]: 1}]")).contains("keys must be text");
    }

    @Test
    void invalidEnvOptionsUseTheOptionErrors() {
        assertThat(Errors.message(
                        ErrorCode.OPTION_INVALID, () -> parse(JOB.replace("parallelism: 4", "paralelism: 4"))))
                .contains("'paralelism'", "did you mean 'parallelism'?");
        assertThat(Errors.message(
                        ErrorCode.OPTION_INVALID,
                        () -> parse(JOB.replace("delivery: exactly-once", "delivery: twice"))))
                .contains("expected one of [AT_LEAST_ONCE, EXACTLY_ONCE]");
        assertThat(Errors.message(
                        ErrorCode.OPTION_INVALID, () -> parse(JOB.replace("parallelism: 4", "parallelism: 0"))))
                .contains("'parallelism'");
    }

    @Test
    void yamlBooleansOnlyMeanTrueAndFalse() {
        JobDefinition job = parse(
                """
                version: 1
                name: flags
                source: [{ id: s, type: fake, a: yes, b: true, c: ~, d: 1.5, e: 2026-11-07, f: 0x10 }]
                sink: [{ id: k, type: console }]
                """);
        assertThat(job.node("s").orElseThrow().options())
                .containsEntry("a", "yes")
                .containsEntry("b", true)
                .containsEntry("c", null)
                .containsEntry("d", 1.5)
                .containsEntry("e", "2026-11-07")
                .containsEntry("f", "0x10");
    }

    @Test
    void locationsPrintLineColumnAndPath() {
        assertThat(new Location(3, 7, "sink[0].inputs")).hasToString("line 3, column 7 (sink[0].inputs)");
        assertThat(Location.of("")).hasToString("the document");
        assertThat(Location.of("env").child("variables").child("[2]")).hasToString("env.variables[2]");
        assertThat(Suggestions.closest("srouce", List.of("source", "sink"))).contains("source");
        assertThat(Suggestions.closest("zzz", List.of("source"))).isEmpty();
    }
}
