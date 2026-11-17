/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.TestkitExtension;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.RowAssertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;

/** Jobs with the MySQL source on every verified version: many tables, queries and errors. */
@ExtendWith(TestkitExtension.class)
class MySqlSourceTest {

    private static final TableId ORDERS = TableId.of("sales", "orders");
    private static final TableId REFUNDS = TableId.of("sales", "refunds");
    private static final TableId ORDERS_BAK = TableId.of("sales", "orders_bak");
    private static final TableId USERS = TableId.of("crm", "users");

    @BeforeAll
    static void docker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    private static CatalogTable table(TableId id) {
        return CatalogTable.of(
                        id,
                        List.of(
                                Column.of("id", DataTypes.INT64().notNull()),
                                Column.of("amount", DataTypes.DECIMAL(10, 2)),
                                Column.of("note", DataTypes.STRING(20))))
                .withPrimaryKey("id");
    }

    private static List<Row> rows(TableId id, int count) {
        List<Row> rows = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            rows.add(GenericRow.of(id, i, BigDecimal.valueOf(i * 25, 2), i % 4 == 0 ? null : id.table() + i));
        }
        return rows;
    }

    private static List<Row> read(TestkitEngine engine, Map<String, Object> options, int parallelism) {
        String out = Datasets.output();
        try {
            engine.run(TestJob.named("read")
                            .source(new MySqlSourceFactory(), options)
                            .sink(TestkitConnectors.collect(), Map.of("dataset", out))
                            .parallelism(parallelism))
                    .requireSuccess()
                    .requireReconciled();
            return Datasets.written(out);
        } finally {
            Datasets.release(out);
        }
    }

    @ParameterizedTest(name = "MySQL {0}")
    @ValueSource(strings = {"8.0", "8.4"})
    void readsEveryTableThePatternsSelect(String version, TestkitEngine engine) throws Exception {
        MySql mysql = new MySql(version);
        List<Row> expected = new ArrayList<>();
        for (TableId id : List.of(ORDERS, REFUNDS)) {
            List<Row> rows = rows(id, id == ORDERS ? 3000 : 700);
            mysql.create(table(id), rows);
            expected.addAll(rows);
        }
        mysql.create(table(ORDERS_BAK), rows(ORDERS_BAK, 10));
        mysql.create(table(USERS), rows(USERS, 10));
        Map<String, Object> options = new LinkedHashMap<>(mysql.options(ORDERS));
        options.remove("table");
        options.put("tables.include", List.of("sales\\..*", "mysql\\..*"));
        options.put("tables.exclude", List.of(".*_bak"));
        options.put("split.count", 4);

        List<Row> rows = read(engine, options, 3);
        RowAssertions.assertSameRows("rows of sales", expected, rows);
        assertThat(rows).filteredOn(r -> r.table().equals(REFUNDS)).hasSize(700);

        TestJob job = TestJob.named("plan")
                .source(new MySqlSourceFactory(), options)
                .sink(TestkitConnectors.collect(), Map.of("dataset", "x"));
        assertThat(job.plan().node("src").orElseThrow().outputTables())
                .as("system databases are never read")
                .extracting(t -> t.id().toQualifiedName())
                .containsExactly("sales.orders", "sales.refunds");
    }

    @ParameterizedTest(name = "MySQL {0}")
    @ValueSource(strings = {"8.0", "8.4"})
    void readsAQueryAndReportsAMissingTable(String version, TestkitEngine engine) throws Exception {
        MySql mysql = new MySql(version);
        mysql.create(table(ORDERS), rows(ORDERS, 100));
        Map<String, Object> options = new LinkedHashMap<>(mysql.options(ORDERS));
        options.remove("table");
        options.put("query", "SELECT id, amount FROM sales.orders WHERE id < 10");
        assertThat(read(engine, options, 2)).hasSize(10);

        Map<String, Object> missing = new LinkedHashMap<>(mysql.options(TableId.of("sales", "nothing")));
        TestJob job = TestJob.named("missing")
                .source(new MySqlSourceFactory(), missing)
                .sink(TestkitConnectors.collect(), Map.of("dataset", "x"));
        assertThatThrownBy(job::plan).isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                .isEqualTo(ErrorCode.TABLE_NOT_FOUND));
    }
}
