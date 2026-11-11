/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.AddColumn;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorRegistry;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.testkit.ContractGroup;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestRun;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.TransformUnderTest;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.RowAssertions;
import org.devlive.pipeforge.testkit.data.TestData;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.DynamicNode;
import org.opentest4j.AssertionFailedError;

/**
 * The groups of a transform: {@code options} and {@code transform}. The {@code transform} group
 * runs the transform between the testkit's source and sink and compares what arrives with the
 * transform's reference model: the derived schema, the rows (empty input, many rows, parallelism
 * 4, several tables), schema changes, and failures reported at the node with a registered code.
 */
public final class TransformGroups {

    /** The id the transform node has in testkit jobs. */
    public static final String NODE = "t1";

    private TransformGroups() {}

    public static List<ContractGroup> all(TransformUnderTest transform) {
        return List.of(options(transform), transform(transform));
    }

    public static ContractGroup options(TransformUnderTest transform) {
        return new ContractGroup("options", engine -> {
            TestTable table = TestData.rows("options", transform.input(), 3, 1);
            String in = Datasets.input(table);
            return OptionsGroup.cases(
                    transform.factory(), NODE, new LinkedHashMap<>(transform.options()), options -> job(
                                    "options", in, transform, options, Datasets.output())
                            .parallelism(1));
        });
    }

    public static ContractGroup transform(TransformUnderTest transform) {
        return new ContractGroup("transform", engine -> {
            CatalogTable input = transform.input();
            List<DynamicNode> cases = new ArrayList<>();
            cases.add(Cases.test("derives the output schema", () -> {
                TestTable table = TestData.rows("schema", input, 1, 1);
                String in = Datasets.input(table);
                try {
                    JobPlan plan = job("schema", in, transform, transform.options(), Datasets.output())
                            .plan();
                    PlannedNode node = plan.node(NODE).orElseThrow();
                    assertEquals(
                            transform.expectedTables(List.of(input)),
                            node.outputTables(),
                            "tables derived by the transform");
                } finally {
                    Datasets.release(in);
                }
            }));
            cases.add(Cases.test(
                    "an empty input", () -> run(engine, transform, List.of(TestData.rows("empty", input, 0, 1)), 1)));
            cases.add(Cases.test(
                    "1,000 rows", () -> run(engine, transform, List.of(TestData.rows("rows", input, 1000, 2)), 1)));
            cases.add(Cases.test(
                    "1,000 rows at parallelism 4",
                    () -> run(engine, transform, List.of(TestData.rows("parallel", input, 1000, 3)), 4)));
            if (transform.supportsMultipleTables()) {
                cases.add(Cases.test("several input tables", () -> {
                    CatalogTable other = input.withId(TableId.of("testkit", "others"));
                    run(
                            engine,
                            transform,
                            List.of(TestData.rows("first", input, 200, 4), TestData.rows("second", other, 200, 5)),
                            2);
                }));
            } else {
                cases.add(Cases.skipped("several input tables", "the transform takes a single table"));
            }
            cases.add(Cases.test("passes schema changes on", () -> schemaChanges(engine, transform)));
            Optional<Map<String, Object>> failing = transform.failingOptions();
            if (failing.isPresent()) {
                cases.add(Cases.test(
                        "reports a failing row at the node", () -> failure(engine, transform, failing.get())));
            } else {
                cases.add(Cases.skipped("reports a failing row at the node", "no option makes a row fail"));
            }
            return cases;
        });
    }

    /** A job testkit-rows → the transform → testkit-collect. */
    static TestJob job(String name, String in, TransformUnderTest transform, Map<String, Object> options, String out) {
        return TestJob.named(name)
                .source(TestkitConnectors.rows(), Map.of("dataset", in))
                .transform(transform.factory(), options)
                .sink(TestkitConnectors.collect(), Map.of("dataset", out));
    }

    /** Runs the tables through the transform and compares the rows that arrive with the model. */
    public static TestRun run(
            TestkitEngine engine, TransformUnderTest transform, List<TestTable> tables, int parallelism)
            throws Exception {
        List<String> ins = tables.stream().map(Datasets::input).toList();
        String out = Datasets.output();
        try {
            TestJob job = TestJob.named("transform-" + tables.get(0).name())
                    .source(
                            TestkitConnectors.rows(),
                            Map.of("dataset", String.join(",", ins), "splits", Math.max(1, parallelism * 2)))
                    .transform(transform.factory(), transform.options())
                    .sink(TestkitConnectors.collect(), Map.of("dataset", out))
                    .parallelism(parallelism);
            TestRun run = engine.run(job).requireSuccess().requireReconciled();
            List<Row> expected = new ArrayList<>();
            int rowsIn = 0;
            for (TestTable table : tables) {
                rowsIn += table.rows().size();
                for (Row row : table.rows()) {
                    expected.addAll(transform.expectedRows(table.table(), row));
                }
            }
            assertEquals(rowsIn, run.metrics().rowsRead(), "rowsRead");
            List<Row> written = Datasets.written(out);
            RowAssertions.assertSameRows("rows after the transform", expected, written);
            List<CatalogTable> derived = transform.expectedTables(
                    tables.stream().map(TestTable::table).toList());
            for (Row row : written) {
                CatalogTable table = derived.stream()
                        .filter(t -> t.id().equals(row.table()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionFailedError(
                                "a row of " + row.table() + " arrived, but the transform derives no such table"));
                conforms(table, row);
            }
            return run;
        } finally {
            ins.forEach(Datasets::release);
            Datasets.release(out);
        }
    }

    /** A row has a value for every column, and none is NULL where the column is NOT NULL. */
    static void conforms(CatalogTable table, Row row) {
        assertEquals(
                table.columns().size(),
                row.arity(),
                "a row of " + table.id() + " has " + row.arity() + " values for "
                        + table.columns().size() + " columns");
        for (int i = 0; i < row.arity(); i++) {
            Column column = table.columns().get(i);
            if (!column.type().isNullable() && row.get(i) == null) {
                throw new AssertionFailedError("column " + column.name() + " is NOT NULL, but a row has NULL in it: "
                        + RowAssertions.canonical(row));
            }
        }
    }

    private static void schemaChanges(TestkitEngine engine, TransformUnderTest transform) throws Exception {
        TestTable table = TestData.rows("schema-change", transform.input(), 10, 6);
        String in = Datasets.input(table);
        String out = Datasets.output();
        try {
            TestJob job = TestJob.named("schema-change")
                    .source(TestkitConnectors.rows(), Map.of("dataset", in, "splits", 1, "schema-change", true))
                    .transform(transform.factory(), transform.options())
                    .sink(TestkitConnectors.collect(), Map.of("dataset", out));
            engine.run(job).requireSuccess();
            SchemaChangeEvent sent = new AddColumn(
                    table.table().id(), Column.of(TestkitConnectors.ADDED_COLUMN, DataTypes.STRING()), null);
            List<SchemaChangeEvent> expected = transform
                    .expectedSchemaChange(List.of(table.table()), sent)
                    .map(List::of)
                    .orElse(List.of());
            assertEquals(expected, Datasets.schemaChanges(out), "schema changes after the transform");
        } finally {
            Datasets.release(in);
            Datasets.release(out);
        }
    }

    private static void failure(TestkitEngine engine, TransformUnderTest transform, Map<String, Object> options)
            throws Exception {
        TestTable table = TestData.rows("failing", transform.input(), 10, 7);
        String in = Datasets.input(table);
        String out = Datasets.output();
        try {
            TestRun run = engine.run(job("failing", in, transform, options, out));
            assertEquals(JobStatus.FAILED, run.status(), "the job status when a row fails");
            PipeForgeException error = run.result().error();
            assertNotNull(error, "the error of the failed job");
            assertTrue(
                    ErrorRegistry.global().find(error.code()).isPresent(),
                    "the error code " + error.code() + " is registered");
            assertEquals(NODE, error.scope().node(), "the node in the scope of " + error.getMessage());
        } finally {
            Datasets.release(in);
            Datasets.release(out);
        }
    }
}
