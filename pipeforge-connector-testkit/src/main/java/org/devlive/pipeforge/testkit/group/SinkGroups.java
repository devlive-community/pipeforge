/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.group;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.testkit.ContractGroup;
import org.devlive.pipeforge.testkit.SinkUnderTest;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestRun;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.RowAssertions;
import org.devlive.pipeforge.testkit.data.TestData;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.DynamicNode;

/** The groups that apply to sinks: each job reads {@code testkit-rows} and writes with the sink under test. */
public final class SinkGroups {

    private SinkGroups() {}

    public static List<ContractGroup> all(SinkUnderTest sink) {
        return List.of(options(sink), types(sink), batch(sink));
    }

    public static ContractGroup options(SinkUnderTest sink) {
        return new ContractGroup("options", engine -> {
            TestTable table = TestData.rows("options", TestData.wideTable(), 3, 1);
            String in = Datasets.input(table);
            Map<String, Object> baseline = SourceGroups.call(() -> sink.options(table));
            return OptionsGroup.cases(sink.factory(), "out", baseline, options -> TestJob.named("options")
                    .source(TestkitConnectors.rows(), Map.of("dataset", in))
                    .sink(sink.factory(), options));
        });
    }

    public static ContractGroup types(SinkUnderTest sink) {
        return new ContractGroup("types", engine -> {
            List<DynamicNode> cases = new ArrayList<>();
            for (DataType type : TestData.TYPES) {
                String name = TestData.name(type);
                if (!sink.supports(type)) {
                    cases.add(Cases.skipped(name, "the sink does not support " + type.root()));
                } else {
                    cases.add(Cases.test(name, () -> write(engine, sink, TestData.typeTable(type), 1)));
                }
            }
            return cases;
        });
    }

    public static ContractGroup batch(SinkUnderTest sink) {
        return new ContractGroup("batch", engine -> {
            CatalogTable wide = TestData.wideTable();
            List<DynamicNode> cases = new ArrayList<>();
            cases.add(Cases.test("an empty table", () -> write(engine, sink, TestData.rows("empty", wide, 0, 1), 1)));
            cases.add(Cases.test("a single row", () -> write(engine, sink, TestData.rows("single", wide, 1, 2), 1)));
            int large = sink.largeRowCount();
            for (int parallelism : SourceGroups.PARALLELISM) {
                cases.add(Cases.test(
                        String.format("%,d rows at parallelism %d", large, parallelism),
                        () -> write(engine, sink, TestData.rows("large-" + parallelism, wide, large, 3), parallelism)));
            }
            return cases;
        });
    }

    /** Writes {@code table} with the sink and checks the row counters and, when possible, what was written. */
    public static TestRun write(TestkitEngine engine, SinkUnderTest sink, TestTable table, int parallelism)
            throws Exception {
        String in = Datasets.input(table);
        Map<String, Object> options = sink.options(table);
        try {
            TestJob job = TestJob.named("write-" + table.name())
                    .source(TestkitConnectors.rows(), Map.of("dataset", in, "splits", Math.max(1, parallelism * 2)))
                    .sink(sink.factory(), options)
                    .parallelism(parallelism);
            TestRun run = engine.run(job).requireSuccess().requireReconciled();
            assertEquals(table.rows().size(), run.metrics().rowsWritten(), "rowsWritten");
            Optional<List<Row>> written = sink.readBack(table, options);
            if (written.isPresent()) {
                RowAssertions.assertSameRows("rows written by the sink", table.rows(), written.get());
            }
            return run;
        } finally {
            Datasets.release(in);
            sink.cleanup(table, options);
        }
    }
}
