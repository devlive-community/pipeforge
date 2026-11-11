/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.local.LocalEngine;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestRun;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.TestkitExtension;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.RowAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Jobs that read from the fake source on the local engine. */
@ExtendWith(TestkitExtension.class)
class FakeJobsTest {

    private static TestJob job(Map<String, ?> options, String out, int parallelism) {
        return TestJob.named("fake")
                .source(new FakeSourceFactory(), options)
                .sink(TestkitConnectors.collect(), Map.of("dataset", out))
                .parallelism(parallelism);
    }

    private static List<Row> read(TestkitEngine engine, Map<String, ?> options, int parallelism) {
        String out = Datasets.output();
        try {
            engine.run(job(options, out, parallelism)).requireSuccess().requireReconciled();
            return Datasets.written(out);
        } finally {
            Datasets.release(out);
        }
    }

    @Test
    void generatesTheDefaultTable(TestkitEngine engine) {
        String out = Datasets.output();
        TestRun run = engine.run(job(Map.of(), out, 1)).requireSuccess();
        List<Row> rows = Datasets.written(out);

        assertThat(run.metrics().rowsRead()).isEqualTo(1000);
        assertThat(rows).hasSize(1000);
        assertThat(rows.stream().map(r -> (Long) r.get(0)).distinct()).hasSize(1000);
        assertThat(rows).allSatisfy(r -> assertThat(r.table().toString()).isEqualTo("fake.rows"));
        long nulls = rows.stream().filter(r -> r.get(1) == null).count();
        assertThat(nulls).isBetween(50L, 150L);
        assertThat(rows.stream().map(r -> (String) r.get(1)).filter(n -> n != null))
                .allSatisfy(name -> assertThat(name.length()).isBetween(1, 16));
        PlannedNode src = job(Map.of(), out, 1).plan().node("src").orElseThrow();
        assertThat(src.outputTables().get(0).primaryKey()).containsExactly("id");
        Datasets.release(out);
    }

    @Test
    void producesTheSameRowsForTheSameSeedAtAnyParallelism(TestkitEngine engine) {
        Map<String, Object> options = Map.of("rows", 5000, "seed", 7, "splits", 9);
        List<Row> one = read(engine, options, 1);
        List<Row> four = read(engine, options, 4);

        RowAssertions.assertSameRows("parallelism 4", one, four);
        List<Row> other = read(engine, Map.of("rows", 5000, "seed", 8), 1);
        assertThat(other.stream().map(RowAssertions::canonical).toList())
                .isNotEqualTo(one.stream().map(RowAssertions::canonical).toList());
    }

    @Test
    void generatesManyTablesWithTheSameColumns(TestkitEngine engine) {
        List<Row> rows = read(
                engine, Map.of("tables", List.of("shop.orders_0", "shop.orders_1", "shop.orders_2"), "rows", 10), 2);

        assertThat(rows).hasSize(30);
        assertThat(rows.stream().map(r -> r.table().toString()).distinct())
                .containsExactlyInAnyOrder("shop.orders_0", "shop.orders_1", "shop.orders_2");
        JobPlan plan = job(Map.of("tables", List.of("a.x", "a.y")), "unused", 1).plan();
        assertThat(plan.node("src").orElseThrow().outputTables()).hasSize(2);
    }

    @Test
    void writesNullsAndBadValuesAsConfigured(TestkitEngine engine) {
        List<String> columns = List.of("id INT32 NOT NULL", "code STRING(3)", "total DECIMAL(4, 1)", "note STRING");
        List<Row> noNulls =
                read(engine, Map.of("columns", columns, "primary-key", List.of("id"), "null-ratio", 0, "rows", 200), 1);
        assertThat(noNulls).allSatisfy(r -> {
            assertThat(r.get(1)).isNotNull();
            assertThat(((String) r.get(1)).length()).isLessThanOrEqualTo(3);
            assertThat(((java.math.BigDecimal) r.get(2)).precision()).isLessThanOrEqualTo(4);
        });
        List<Row> allNulls = read(engine, Map.of("columns", columns, "null-ratio", 1, "rows", 20), 1);
        assertThat(allNulls).allSatisfy(r -> {
            assertThat(r.get(1)).isNull();
            assertThat(r.get(3)).isNull();
        });
        List<Row> bad = read(
                engine, Map.of("columns", columns, "primary-key", List.of("id"), "bad-row-ratio", 1, "rows", 20), 1);
        assertThat(bad).allSatisfy(r -> assertThat((String) r.get(1)).isEqualTo("xxxx"));
        List<Row> badDecimal = read(
                engine,
                Map.of(
                        "columns",
                        List.of("id INT64 NOT NULL", "total DECIMAL(4, 1)"),
                        "primary-key",
                        List.of("id"),
                        "bad-row-ratio",
                        1,
                        "rows",
                        5),
                1);
        assertThat(badDecimal).allSatisfy(r -> assertThat(r.get(1)).isEqualTo(new java.math.BigDecimal("1000.0")));
        List<Row> badNotNull = read(
                engine,
                Map.of(
                        "columns",
                        List.of("id INT64 NOT NULL", "must STRING NOT NULL"),
                        "primary-key",
                        List.of("id"),
                        "bad-row-ratio",
                        1,
                        "rows",
                        5),
                1);
        assertThat(badNotNull).allSatisfy(r -> assertThat(r.get(1)).isNull());
    }

    @Test
    void limitsTheRate(TestkitEngine engine) {
        long start = System.nanoTime();
        List<Row> rows = read(engine, Map.of("rows", 300, "rows-per-second", 1000, "splits", 2), 2);

        assertThat(rows).hasSize(300);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(150));
    }

    @Test
    void generatesForeverWhenUnbounded() throws Exception {
        String out = Datasets.output();
        TestJob job = job(Map.of("unbounded", true, "splits", 3), out, 2);
        assertThat(job.plan().node("src").orElseThrow().boundedness()).isEqualTo(Boundedness.UNBOUNDED);
        try (LocalEngine engine = new LocalEngine()) {
            JobHandle handle = engine.submit(job.plan());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (handle.metrics().rowsRead() < 5000 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(handle.status()).isEqualTo(JobStatus.RUNNING);
            handle.cancel();
            assertThat(handle.result().get(30, TimeUnit.SECONDS).status()).isEqualTo(JobStatus.CANCELLED);
        }
        List<Row> rows = Datasets.written(out);
        assertThat(rows.stream().map(r -> (Long) r.get(0)).distinct().count()).isEqualTo(rows.size());
        Datasets.release(out);
    }
}
