/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.devlive.pipeforge.core.TestComponents.ORDERS;
import static org.devlive.pipeforge.core.TestComponents.plan;

import java.util.Map;
import org.devlive.pipeforge.api.factory.EngineKind;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.Errors;
import org.devlive.pipeforge.core.TestComponents;
import org.devlive.pipeforge.core.job.Delivery;
import org.devlive.pipeforge.core.job.JobMode;
import org.devlive.pipeforge.core.job.NodeKind;
import org.junit.jupiter.api.Test;

class PlannerTest {

    private static final String LINEAR =
            """
            version: 1
            name: orders
            env:
              parallelism: 2
              source: { fetch-size: 500, rows: 7 }
            source:
              - { id: src, type: fake, rows: 100, password: secret }
            transform:
              - { id: map, type: rename, from: name, to: customer }
            sink:
              - { id: out, type: console, parallelism: 1 }
            """;

    private static String job(String nodes) {
        return "version: 1\nname: t\n" + nodes;
    }

    @Test
    void plansNodesInTopologicalOrderWithSchemas() {
        JobPlan plan = plan(LINEAR);
        assertThat(plan.name()).isEqualTo("orders");
        assertThat(plan.engine()).isEqualTo(EngineKind.NATIVE);
        assertThat(plan.mode()).isEqualTo(JobMode.BATCH);
        assertThat(plan.modeDerived()).isTrue();
        assertThat(plan.delivery()).isEqualTo(Delivery.AT_LEAST_ONCE);
        assertThat(plan.parallelism()).isEqualTo(2);
        assertThat(plan.timeZone()).isEqualTo(TestComponents.SHANGHAI);
        assertThat(plan.nodes()).extracting(PlannedNode::id).containsExactly("src", "map", "out");
        assertThat(plan.nodes()).extracting(PlannedNode::parallelism).containsExactly(2, 2, 1);
        assertThat(plan.nodes(NodeKind.SOURCE))
                .extracting(PlannedNode::boundedness)
                .containsExactly(Boundedness.BOUNDED);
        assertThat(plan.downstream("src")).containsExactly("map");
        assertThat(plan.warnings()).isEmpty();

        PlannedNode src = plan.node("src").orElseThrow();
        assertThat(src.plugin()).isEqualTo(StaticComponents.PLUGIN);
        assertThat(src.inputTables()).isEmpty();
        assertThat(src.outputTables())
                .singleElement()
                .extracting(CatalogTable::id)
                .isEqualTo(ORDERS);

        PlannedNode map = plan.node("map").orElseThrow();
        assertThat(map.inputTables()).isEqualTo(src.outputTables());
        assertThat(map.outputTables().get(0).columnNames()).containsExactly("id", "customer", "amount", "created_at");
        assertThat(map.inputsImplied()).isTrue();

        PlannedNode out = plan.node("out").orElseThrow();
        assertThat(out.inputTables()).isEqualTo(map.outputTables());
        assertThat(out.outputTables()).isEmpty();
        assertThat(out.component().factory().identifier()).isEqualTo("console");
    }

    @Test
    void optionsComeFromTheNodeThenEnvThenClusterThenTheDefault() {
        ReadonlyConfig cluster =
                ReadonlyConfig.of(Map.of("source.fetch-size", 900, "source.table", "demo.orders", "sink.limit", 5));
        PlannedNode src = TestComponents.plan(LINEAR, cluster).node("src").orElseThrow();
        assertThat(src.config().toMap())
                .containsEntry("rows", 100L)
                .containsEntry("fetch-size", 500L)
                .containsEntry("table", "demo.orders");
        assertThat(src.options())
                .containsExactly(
                        new PlannedOption("rows", 100L, OptionOrigin.NODE, false),
                        new PlannedOption("password", "******", OptionOrigin.NODE, true),
                        new PlannedOption("table", "demo.orders", OptionOrigin.CLUSTER, false),
                        new PlannedOption("fetch-size", 500L, OptionOrigin.ENV, false));
        PlannedNode out = TestComponents.plan(LINEAR, cluster).node("out").orElseThrow();
        assertThat(out.options()).containsExactly(new PlannedOption("limit", 5, OptionOrigin.CLUSTER, false));

        PlannedNode defaults = plan(LINEAR).node("out").orElseThrow();
        assertThat(defaults.options()).containsExactly(new PlannedOption("limit", 20, OptionOrigin.DEFAULT, false));
    }

    @Test
    void envDefaultsForMapOptionsCopyEveryEntry() {
        JobPlan plan = plan(
                job(
                        """
                env:
                  source.properties: { useSSL: "false", timeout: 5 }
                source: [{ id: s, type: fake }]
                sink: [{ id: k, type: console }]
                """));
        assertThat(plan.node("s").orElseThrow().config().nativeConfig("properties"))
                .containsExactly(Map.entry("useSSL", "false"), Map.entry("timeout", "5"));
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void unusedEnvDefaultsAndEmptyOutputsAreWarnings() {
        JobPlan plan = plan(
                job(
                        """
                env:
                  sink: { batch-size: 10 }
                  transform.from: x
                source: [{ id: s, type: fake }]
                transform: [{ id: d, type: drop-all }]
                sink: [{ id: k, type: console }]
                """));
        assertThat(plan.warnings())
                .containsExactly(
                        "node 'd' produces no tables",
                        "env.sink.batch-size is not an option of any sink in the job",
                        "env.transform.from is not an option of any transform in the job");
    }

    @Test
    void pluginOptionsAreValidated() {
        assertThat(Errors.message(ErrorCode.OPTION_INVALID, () -> plan(LINEAR.replace("rows: 100", "rowz: 100"))))
                .isEqualTo(
                        "Option 'rowz' of node 'src' has the invalid value '100': unknown option; did you mean 'rows'?");
        assertThat(Errors.message(ErrorCode.OPTION_INVALID, () -> plan(LINEAR.replace("rows: 100", "rows: -1"))))
                .contains("'rows'", "'-1'");
        assertThat(Errors.message(
                        ErrorCode.OPTION_REQUIRED, () -> plan(LINEAR.replace("to: customer", "too: customer"))))
                .contains("'to'", "'map'");
        assertThat(Errors.thrown(ErrorCode.OPTION_REQUIRED, () -> plan(LINEAR.replace("from: name, ", "")))
                        .scope()
                        .node())
                .isEqualTo("map");
    }

    @Test
    void inputsMustNameSourcesOrTransforms() {
        String base =
                """
                source: [{ id: src, type: fake }]
                transform: [{ id: map, type: rename, from: a, to: b, inputs: %s }]
                sink: [{ id: out, type: console }, { id: out2, type: console, inputs: [map] }]
                """;
        assertThat(Errors.message(ErrorCode.INPUT_INVALID, () -> plan(job(base.formatted("[sr]")))))
                .isEqualTo("Node 'map' cannot read from 'sr': the job has no node with this id; did you mean 'src'?");
        assertThat(Errors.message(ErrorCode.INPUT_INVALID, () -> plan(job(base.formatted("[map]")))))
                .contains("a node cannot read its own output");
        assertThat(Errors.message(ErrorCode.INPUT_INVALID, () -> plan(job(base.formatted("[src, src]")))))
                .contains("listed twice");
        assertThat(Errors.message(ErrorCode.INPUT_INVALID, () -> plan(job(base.formatted("[out]")))))
                .contains("'out' is a sink and produces no data");
    }

    @Test
    void cyclesAreReportedAsAPath() {
        String yaml = job(
                """
                source: [{ id: src, type: fake }]
                transform:
                  - { id: a, type: rename, from: x, to: y, inputs: [src, c] }
                  - { id: b, type: rename, from: x, to: y, inputs: [a] }
                  - { id: c, type: rename, from: x, to: y, inputs: [b] }
                sink: [{ id: out, type: console, inputs: [c] }]
                """);
        assertThat(Errors.message(ErrorCode.DAG_CYCLE, () -> plan(yaml)))
                .isEqualTo("The job contains a cycle: a -> b -> c -> a");
    }

    @Test
    void everyNodeMustLeadToASink() {
        String yaml = job(
                """
                source: [{ id: a, type: fake }, { id: b, type: fake }]
                sink: [{ id: out, type: console }]
                """);
        PipeForgeException error = Errors.thrown(ErrorCode.NODE_DISCONNECTED, () -> plan(yaml));
        assertThat(error.localizedMessage("en-US")).isEqualTo("The output of node 'a' does not reach any sink");
        assertThat(error.scope().node()).isEqualTo("a");
    }

    @Test
    void schemasOfSeveralInputsAreMerged() {
        JobPlan plan = plan(
                job(
                        """
                source: [{ id: a, type: fake }, { id: b, type: events }, { id: c, type: fake, table: demo.users }]
                sink: [{ id: out, type: console, inputs: [a, b, c] }]
                """));
        PlannedNode out = plan.node("out").orElseThrow();
        assertThat(out.inputTables()).extracting(t -> t.id().toString()).containsExactly("demo.orders", "demo.users");
        CatalogTable orders = out.inputTables().get(0);
        assertThat(orders.columns())
                .containsExactly(
                        Column.of("id", DataTypes.INT64().notNull()),
                        Column.of("name", DataTypes.STRING(40)).withComment("customer name"),
                        Column.of("amount", DataTypes.DECIMAL(10, 2)),
                        Column.of("created_at", DataTypes.TIMESTAMP(3)),
                        Column.of("channel", DataTypes.STRING()));
        assertThat(orders.primaryKey()).isEmpty();
        assertThat(plan.mode()).isEqualTo(JobMode.STREAMING);
    }

    @Test
    void incompatibleSchemasNameBothInputs() {
        String yaml = job(
                """
                source: [{ id: a, type: fake }, { id: b, type: clicks }]
                sink: [{ id: out, type: console, inputs: [a, b] }]
                """);
        assertThat(Errors.message(ErrorCode.SCHEMA_INCOMPATIBLE, () -> plan(yaml)))
                .isEqualTo("Node 'out' cannot accept the schema produced by 'a, b': column 'id' of table demo.orders"
                        + " is INT64 NOT NULL in 'a' and STRING in 'b': the types cannot be merged");
    }

    @Test
    void sinksCheckWhatTheyReceive() {
        String yaml = job(
                """
                source: [{ id: a, type: events }]
                sink: [{ id: out, type: keyed }]
                """);
        assertThat(Errors.message(ErrorCode.UPSERT_REQUIRES_PK, () -> plan(yaml)))
                .contains("'out'", "demo.orders");
        assertThat(plan(job("source: [{ id: a, type: fake }]\nsink: [{ id: out, type: keyed }]"))
                        .nodes())
                .hasSize(2);
    }

    @Test
    void theModeIsDerivedOrChecked() {
        String streaming =
                job("env: { mode: batch }\nsource: [{ id: a, type: events }]\nsink: [{ id: k, type: console }]");
        PipeForgeException error = Errors.thrown(ErrorCode.BOUNDEDNESS_MISMATCH, () -> plan(streaming));
        assertThat(error.localizedMessage("en-US"))
                .isEqualTo("The job runs in batch mode, but source 'a' is unbounded");
        assertThat(error.scope().node()).isEqualTo("a");

        JobPlan written = plan(
                job("env: { mode: streaming }\nsource: [{ id: a, type: fake }]\nsink: [{ id: k, type: console }]"));
        assertThat(written.mode()).isEqualTo(JobMode.STREAMING);
        assertThat(written.modeDerived()).isFalse();

        JobPlan fromCluster = TestComponents.plan(
                job("source: [{ id: a, type: fake }]\nsink: [{ id: k, type: console }]"),
                ReadonlyConfig.of(Map.of("mode", "streaming", "parallelism", 3)));
        assertThat(fromCluster.mode()).isEqualTo(JobMode.STREAMING);
        assertThat(fromCluster.parallelism()).isEqualTo(3);
    }

    @Test
    void exactlyOnceNeedsCommittingOrIdempotentSinks() {
        String template =
                job("env: { delivery: exactly-once }\nsource: [{ id: a, type: fake }]\nsink: [{ id: k, type: %s }]");
        assertThat(Errors.message(ErrorCode.DELIVERY_UNSUPPORTED, () -> plan(template.formatted("console"))))
                .isEqualTo("Exactly-once delivery is required, but sink 'k' only supports at-least-once");
        assertThat(plan(template.formatted("committed")).delivery()).isEqualTo(Delivery.EXACTLY_ONCE);
        assertThat(plan(template.formatted("keyed")).delivery()).isEqualTo(Delivery.EXACTLY_ONCE);
    }

    @Test
    void componentProblemsKeepTheirCodes() {
        assertThat(Errors.message(
                        ErrorCode.PLUGIN_NOT_FOUND, () -> plan(LINEAR.replace("type: console", "type: kafka"))))
                .contains("kafka");
        assertThat(Errors.message(
                        ErrorCode.PLUGIN_NOT_FOUND,
                        () -> plan(LINEAR.replace("type: console,", "type: console, plugin: other,"))))
                .contains("other");
        String broken = job(
                "source: [{ id: a, type: fake }]\ntransform: [{ id: t, type: broken }]\nsink: [{ id: k, type: console }]");
        assertThat(Errors.message(ErrorCode.PLUGIN_CALL_FAILED, () -> plan(broken)))
                .contains("derive the tables of t", "IllegalStateException: cannot derive");
    }
}
