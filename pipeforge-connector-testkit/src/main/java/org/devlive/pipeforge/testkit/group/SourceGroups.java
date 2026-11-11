/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.group;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.testkit.ContractGroup;
import org.devlive.pipeforge.testkit.SourceUnderTest;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestRun;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.RowAssertions;
import org.devlive.pipeforge.testkit.data.TestData;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.DynamicNode;
import org.opentest4j.AssertionFailedError;

/** The groups that apply to sources: each job reads with the source under test into {@code testkit-collect}. */
public final class SourceGroups {

    /** The parallelism of the large cases of the {@code batch} group. */
    public static final List<Integer> PARALLELISM = List.of(1, 4, 16);

    private SourceGroups() {}

    public static List<ContractGroup> all(SourceUnderTest source) {
        return List.of(options(source), types(source), batch(source));
    }

    public static ContractGroup options(SourceUnderTest source) {
        return new ContractGroup("options", engine -> {
            TestTable table = TestData.rows("options", TestData.wideTable(), 3, 1);
            Map<String, Object> baseline = call(() -> source.options(table));
            return OptionsGroup.cases(source.factory(), "src", baseline, options -> TestJob.named("options")
                    .source(source.factory(), options)
                    .sink(TestkitConnectors.collect(), Map.of("dataset", "unused")));
        });
    }

    public static ContractGroup types(SourceUnderTest source) {
        return new ContractGroup("types", engine -> {
            List<DynamicNode> cases = new ArrayList<>();
            for (DataType type : TestData.TYPES) {
                String name = TestData.name(type);
                if (!source.supports(type)) {
                    cases.add(Cases.skipped(name, "the source does not support " + type.root()));
                } else {
                    cases.add(Cases.test(name, () -> read(engine, source, TestData.typeTable(type), 1, Map.of())));
                }
            }
            return cases;
        });
    }

    public static ContractGroup batch(SourceUnderTest source) {
        return new ContractGroup("batch", engine -> {
            CatalogTable wide = TestData.wideTable();
            List<DynamicNode> cases = new ArrayList<>();
            cases.add(Cases.test(
                    "an empty table", () -> read(engine, source, TestData.rows("empty", wide, 0, 1), 1, Map.of())));
            cases.add(Cases.test(
                    "a single row", () -> read(engine, source, TestData.rows("single", wide, 1, 2), 1, Map.of())));
            int large = source.largeRowCount();
            source.splitStrategies().forEach((strategy, extra) -> {
                for (int parallelism : PARALLELISM) {
                    String name = String.format("%,d rows at parallelism %d (%s)", large, parallelism, strategy);
                    cases.add(Cases.test(
                            name,
                            () -> read(
                                    engine,
                                    source,
                                    TestData.rows("large-" + parallelism, wide, large, 3),
                                    parallelism,
                                    extra)));
                }
            });
            return cases;
        });
    }

    /**
     * Reads {@code table} with the source and checks the schema, every row and the row counters.
     */
    public static TestRun read(
            TestkitEngine engine, SourceUnderTest source, TestTable table, int parallelism, Map<String, Object> extra)
            throws Exception {
        Map<String, Object> options = new LinkedHashMap<>(source.options(table));
        options.putAll(extra);
        String out = Datasets.output();
        try {
            TestJob job = TestJob.named("read-" + table.name())
                    .source(source.factory(), options)
                    .sink(TestkitConnectors.collect(), Map.of("dataset", out))
                    .parallelism(parallelism);
            PlannedNode node = job.plan().node("src").orElseThrow();
            checkSchema(table.table(), node.outputTables());
            TestRun run = engine.run(job).requireSuccess().requireReconciled();
            RowAssertions.assertSameRows("rows read by the source", table.rows(), Datasets.written(out));
            assertEquals(table.rows().size(), run.metrics().rowsRead(), "rowsRead");
            return run;
        } finally {
            Datasets.release(out);
            source.cleanup(table);
        }
    }

    /** The source must produce the table with the same columns and type roots. */
    static void checkSchema(CatalogTable expected, List<CatalogTable> produced) {
        CatalogTable table = produced.stream()
                .filter(t -> t.id().equals(expected.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionFailedError("The source does not produce table " + expected.id()
                        + " but " + produced.stream().map(CatalogTable::id).toList()));
        List<String> want =
                expected.columns().stream().map(SourceGroups::describe).toList();
        List<String> got = table.columns().stream().map(SourceGroups::describe).toList();
        assertEquals(want, got, "columns of " + expected.id());
    }

    private static String describe(Column column) {
        return column.name() + " " + column.type().root();
    }

    interface Call<T> {
        T call() throws Exception;
    }

    static <T> T call(Call<T> action) {
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionFailedError("The connector could not prepare the test: " + e.getMessage(), e);
        }
    }
}
