/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestRun;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.TestkitExtension;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.RowAssertions;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;

/** Jobs with the PostgreSQL sink on every verified version: rows PostgreSQL refuses, and PostgreSQL to PostgreSQL. */
@ExtendWith(TestkitExtension.class)
class PostgresSinkTest {

    private static final TableId PEOPLE = TableId.of("crm", "people");

    /** In the source: names of any length. */
    private static final CatalogTable WIDE = CatalogTable.of(
                    PEOPLE,
                    List.of(Column.of("id", DataTypes.INT64().notNull()), Column.of("name", DataTypes.STRING())))
            .withPrimaryKey("id");

    /** In PostgreSQL: names of at most 8 characters. */
    private static final CatalogTable NARROW = CatalogTable.of(
                    PEOPLE,
                    List.of(Column.of("id", DataTypes.INT64().notNull()), Column.of("name", DataTypes.STRING(8))))
            .withPrimaryKey("id");

    @BeforeAll
    static void docker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    /** 200 people; every 50th name is too long for the target. */
    private static List<Row> people() {
        List<Row> rows = new ArrayList<>();
        for (long i = 0; i < 200; i++) {
            rows.add(GenericRow.of(PEOPLE, i, i % 50 == 7 ? "much-too-long-" + i : "p" + i));
        }
        return rows;
    }

    private static TestRun write(TestkitEngine engine, Postgres postgres, Map<String, Object> dirty) throws Exception {
        postgres.create(NARROW, List.of());
        String in = Datasets.input(new TestTable("people", WIDE, people()));
        try {
            TestJob job = TestJob.named("people")
                    .source(TestkitConnectors.rows(), Map.of("dataset", in, "splits", 4))
                    .sink(new PostgresSinkFactory(), postgres.options(PEOPLE))
                    .parallelism(2);
            if (!dirty.isEmpty()) {
                job.env("dirty", dirty);
            }
            return engine.run(job);
        } finally {
            Datasets.release(in);
        }
    }

    @ParameterizedTest(name = "PostgreSQL {0}")
    @ValueSource(strings = {"14", "16"})
    void skipsTheRowsPostgresRefusesAndWritesTheRest(String version, TestkitEngine engine) throws Exception {
        Postgres postgres = new Postgres(version);
        TestRun run = write(engine, postgres, Map.of("policy", "skip", "max-rows", 10))
                .requireSuccess()
                .requireReconciled();
        assertThat(run.metrics().rowsDirty()).isEqualTo(4);
        assertThat(run.metrics().rowsWritten()).isEqualTo(196);
        assertThat(run.metrics().dirtySamples()).hasSize(4).allSatisfy(s -> {
            assertThat(s.code()).isEqualTo(ErrorCode.BAD_ROW.id());
            assertThat(s.row()).contains("much-too-long-");
        });
        List<Row> expected = people().stream()
                .filter(r -> !String.valueOf(r.get(1)).startsWith("much"))
                .toList();
        RowAssertions.assertSameRows("people", expected, postgres.rows(NARROW));
    }

    @ParameterizedTest(name = "PostgreSQL {0}")
    @ValueSource(strings = {"14", "16"})
    void failsOnTheFirstRefusedRowByDefault(String version, TestkitEngine engine) throws Exception {
        TestRun run = write(engine, new Postgres(version), Map.of());
        assertThat(run.status()).isEqualTo(JobStatus.FAILED);
        assertThat(run.result().error()).isNotNull();
        assertThat(run.result().error().code()).isEqualTo(ErrorCode.BAD_ROW);
        assertThat(run.result().error().getMessage())
                .contains("'out' refused a row")
                .contains("value too long for type character varying(8)");
    }

    @ParameterizedTest(name = "PostgreSQL {0}")
    @ValueSource(strings = {"14", "16"})
    void copiesATableFromPostgresToPostgres(String version, TestkitEngine engine) throws Exception {
        Postgres postgres = new Postgres(version);
        TableId orders = TableId.of("shop", "orders");
        TableId copy = TableId.of("archive", "orders");
        List<Column> columns = List.of(
                Column.of("id", DataTypes.INT64().notNull()),
                Column.of("amount", DataTypes.DECIMAL(10, 2)),
                Column.of("ratio", DataTypes.FLOAT32()),
                Column.of("paid_at", DataTypes.TIMESTAMP_LTZ(3)));
        List<Row> rows = new ArrayList<>();
        for (long i = 0; i < 5000; i++) {
            rows.add(GenericRow.of(
                    orders,
                    i,
                    BigDecimal.valueOf(i * 7, 2),
                    i * 1.1f + 0.123456f,
                    java.time.Instant.ofEpochMilli(1_700_000_000_000L + i * 1000)));
        }
        postgres.create(CatalogTable.of(orders, columns).withPrimaryKey("id"), rows);
        postgres.create(CatalogTable.of(copy, columns).withPrimaryKey("id"), List.of());
        engine.run(TestJob.named("copy")
                        .source(new PostgresSourceFactory(), postgres.options(orders))
                        .sink(new PostgresSinkFactory(), postgres.options(copy))
                        .parallelism(3))
                .requireSuccess()
                .requireReconciled();
        List<Row> copied = postgres.rows(CatalogTable.of(copy, columns)).stream()
                .map(r -> (Row) GenericRow.of(orders, r.get(0), r.get(1), r.get(2), r.get(3)))
                .toList();
        RowAssertions.assertSameRows("copied orders", rows, copied);
    }
}
