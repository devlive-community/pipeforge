/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.devlive.pipeforge.api.type.DataTypes.DECIMAL;
import static org.devlive.pipeforge.api.type.DataTypes.INT32;
import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestRun;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.TestkitExtension;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Jobs that read from and write to H2 through the base, for what the contracts do not cover. */
@ExtendWith(TestkitExtension.class)
class JdbcJobsTest {

    private static final TableId ACCOUNTS = TableId.parse("shop.accounts");
    private static final CatalogTable ACCOUNTS_TABLE = CatalogTable.of(
                    ACCOUNTS, List.of(Column.of("id", INT64().notNull()), Column.of("amount", DECIMAL(12, 2))))
            .withPrimaryKey("id");
    private static final TableId PEOPLE = TableId.parse("shop.people");
    private static final CatalogTable PEOPLE_TABLE = CatalogTable.of(
                    PEOPLE,
                    List.of(Column.of("id", INT64().notNull()), Column.of("grp", INT32()), Column.of("name", STRING())))
            .withPrimaryKey("id");

    private final H2 h2 = new H2("jobs");

    @BeforeEach
    void clean() throws Exception {
        h2.drop(ACCOUNTS);
        h2.drop(PEOPLE);
        h2.execute("CREATE TABLE \"shop\".\"accounts\" (\"id\" BIGINT NOT NULL PRIMARY KEY,"
                + " \"amount\" NUMERIC(12, 2) CHECK (\"amount\" >= 0))");
        FlakyTrigger.FAILURES.set(0);
    }

    /** Accounts 0..count-1; the ids in {@code negative} have a negative amount, which the table refuses. */
    private static TestTable accounts(int count, long... negative) {
        List<Row> rows = new ArrayList<>();
        for (long id = 0; id < count; id++) {
            boolean bad = false;
            for (long n : negative) {
                bad |= n == id;
            }
            rows.add(GenericRow.of(ACCOUNTS, id, new BigDecimal(bad ? "-1.00" : id + ".50")));
        }
        return new TestTable("accounts", ACCOUNTS_TABLE, rows);
    }

    private TestRun write(TestkitEngine engine, TestTable table, Map<String, Object> extra, String policy) {
        String in = Datasets.input(table);
        try {
            Map<String, Object> options = new LinkedHashMap<>(h2.options(ACCOUNTS));
            options.putAll(extra);
            return engine.run(TestJob.named("write")
                    .source(TestkitConnectors.rows(), Map.of("dataset", in, "splits", 2))
                    .sink(new TestFactories.Sink(), options)
                    .env("dirty", Map.of("policy", policy)));
        } finally {
            Datasets.release(in);
        }
    }

    @Test
    void aBatchWithBadRowsIsSplitUntilTheyAreFoundAndSkipped(TestkitEngine engine) throws Exception {
        TestRun run = write(engine, accounts(100, 13, 57, 90), Map.of("batch-size", 32), "skip")
                .requireSuccess()
                .requireReconciled();

        assertThat(run.metrics().rowsWritten()).isEqualTo(97);
        assertThat(run.metrics().rowsDirty()).isEqualTo(3);
        assertThat(run.metrics().node("out"))
                .hasValueSatisfying(n -> assertThat(n.rowsDirty()).isEqualTo(3));
        assertThat(run.metrics().dirtySamples())
                .hasSize(3)
                .allSatisfy(s -> {
                    assertThat(s.node()).isEqualTo("out");
                    assertThat(s.code()).isEqualTo("PF-DATA-0009");
                    assertThat(s.message()).contains("SQLState 23513");
                })
                .extracting(s -> s.row())
                .containsExactlyInAnyOrder("[13, -1.00]", "[57, -1.00]", "[90, -1.00]");
        List<Row> written = h2.rows(ACCOUNTS_TABLE);
        assertThat(written).hasSize(97);
        assertThat(written).extracting(r -> r.get(0)).doesNotContain(13L, 57L, 90L);
    }

    @Test
    void underTheFailPolicyTheFirstBadRowFailsTheJob(TestkitEngine engine) {
        TestRun run = write(engine, accounts(100, 57), Map.of("batch-size", 32), "fail");
        assertThat(run.status()).isEqualTo(JobStatus.FAILED);
        assertThat(run.result().error().code()).isEqualTo(ErrorCode.BAD_ROW);
        assertThat(run.result().error().scope().node()).isEqualTo("out");
        assertThat(run.result().error().getMessage()).contains("SQLState 23513");
    }

    @Test
    void aBatchThatFailsForATransientReasonIsTriedAgain(TestkitEngine engine) throws Exception {
        h2.execute("CREATE TRIGGER \"flaky\" BEFORE INSERT ON \"shop\".\"accounts\" FOR EACH ROW CALL '"
                + FlakyTrigger.class.getName() + "'");
        FlakyTrigger.FAILURES.set(2);
        write(engine, accounts(50), Map.of("batch-size", 20, "retry-backoff", "10ms"), "fail")
                .requireSuccess()
                .requireReconciled();
        assertThat(h2.rows(ACCOUNTS_TABLE)).hasSize(50);

        h2.execute("DELETE FROM \"shop\".\"accounts\"");
        FlakyTrigger.FAILURES.set(1);
        TestRun run = write(engine, accounts(50), Map.of("max-retries", 0), "fail");
        assertThat(run.status()).isEqualTo(JobStatus.FAILED);
        assertThat(run.result().error().code()).isEqualTo(ErrorCode.SINK_FAILED);
        assertThat(run.result().error().getMessage()).contains("SQLState 40001").contains("simulated deadlock");
    }

    @Test
    void theSinkChecksTheTargetWhilePlanning(TestkitEngine engine) throws Exception {
        CatalogTable wider = ACCOUNTS_TABLE.withColumns(List.of(
                Column.of("id", INT64().notNull()), Column.of("amount", DECIMAL(12, 2)), Column.of("note", STRING())));
        String in = Datasets.input(new TestTable("wider", wider, List.of()));
        try {
            TestJob job = TestJob.named("missing-column")
                    .source(TestkitConnectors.rows(), Map.of("dataset", in))
                    .sink(new TestFactories.Sink(), h2.options(ACCOUNTS));
            assertThatThrownBy(job::plan).isInstanceOfSatisfying(PipeForgeException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.COLUMN_NOT_FOUND);
                assertThat(e.params()).containsEntry("column", "note");
            });
            TestJob missing = TestJob.named("missing-table")
                    .source(TestkitConnectors.rows(), Map.of("dataset", in))
                    .sink(new TestFactories.Sink(), h2.options(TableId.parse("shop.nothing")));
            assertThatThrownBy(missing::plan).isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                    .isEqualTo(ErrorCode.TABLE_NOT_FOUND));
        } finally {
            Datasets.release(in);
        }
    }

    private List<Row> read(TestkitEngine engine, Map<String, Object> source, int parallelism) {
        String out = Datasets.output();
        try {
            engine.run(TestJob.named("read")
                            .source(new TestFactories.Source(), source)
                            .sink(TestkitConnectors.collect(), Map.of("dataset", out))
                            .parallelism(parallelism))
                    .requireSuccess()
                    .requireReconciled();
            return Datasets.written(out);
        } finally {
            Datasets.release(out);
        }
    }

    private void people(int count) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (long id = 0; id < count; id++) {
            rows.add(GenericRow.of(PEOPLE, id, id % 7 == 0 ? null : (int) (id % 5), "p" + id));
        }
        h2.create(PEOPLE_TABLE, rows);
    }

    @Test
    void rangesOfANullableColumnReadItsNullsToo(TestkitEngine engine) throws Exception {
        people(100);
        Map<String, Object> options = new LinkedHashMap<>(h2.options(PEOPLE));
        options.put("split.column", "grp");
        options.put("split.count", 3);
        List<Row> rows = read(engine, options, 2);
        assertThat(rows).hasSize(100);
        assertThat(rows).filteredOn(r -> r.get(1) == null).hasSize(15);
    }

    @Test
    void readsAQueryInOneSplitNamedAfterTheNode(TestkitEngine engine) throws Exception {
        people(20);
        List<Row> rows = read(
                engine,
                Map.of(
                        "url", h2.url,
                        "username", "sa",
                        "query", "SELECT \"id\", \"name\" FROM \"shop\".\"people\" WHERE \"id\" < 5"),
                2);
        assertThat(rows).hasSize(5).allSatisfy(r -> assertThat(r.table()).isEqualTo(TableId.of("src")));
    }

    @Test
    void anEmptyTableHasOneEmptyRange(TestkitEngine engine) throws Exception {
        people(0);
        assertThat(read(engine, h2.options(PEOPLE), 2)).isEmpty();
    }

    @Test
    void aSplitColumnMustBeAnInteger(TestkitEngine engine) throws Exception {
        people(10);
        Map<String, Object> options = new LinkedHashMap<>(h2.options(PEOPLE));
        options.put("split.column", "name");
        String out = Datasets.output();
        try {
            TestRun run = engine.run(TestJob.named("bad-split")
                    .source(new TestFactories.Source(), options)
                    .sink(TestkitConnectors.collect(), Map.of("dataset", out)));
            assertThat(run.status()).isEqualTo(JobStatus.FAILED);
            assertThat(run.result().error().code()).isEqualTo(ErrorCode.OPTION_INVALID);
            assertThat(run.result().error().getMessage()).contains("not an integer");
        } finally {
            Datasets.release(out);
        }
    }

    @Test
    void controlledPropertiesAreRefusedWhilePlanning(TestkitEngine engine) throws Exception {
        people(1);
        Map<String, Object> options = new LinkedHashMap<>(h2.options(PEOPLE));
        options.put("jdbc.properties.autocommit", "false");
        String out = Datasets.output();
        try {
            TestJob job = TestJob.named("controlled")
                    .source(new TestFactories.Source(new JdbcConnectionConfigTest.Strict()), options)
                    .sink(TestkitConnectors.collect(), Map.of("dataset", out));
            assertThatThrownBy(job::plan).isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                    .isEqualTo(ErrorCode.NATIVE_KEY_CONTROLLED));
        } finally {
            Datasets.release(out);
        }
    }
}
