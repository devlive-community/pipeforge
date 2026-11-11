/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.devlive.pipeforge.transform.fieldmapper.Fixtures.EXPRESSIONS;
import static org.devlive.pipeforge.transform.fieldmapper.Fixtures.TABLE;
import static org.devlive.pipeforge.transform.fieldmapper.Fixtures.mapping;
import static org.devlive.pipeforge.transform.fieldmapper.Fixtures.row;
import static org.devlive.pipeforge.transform.fieldmapper.MapperConfigTest.problem;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class TableMappingTest {

    private static List<String> names(CatalogTable table) {
        return table.columnNames();
    }

    private static Column column(CatalogTable table, String name) {
        return table.column(name).orElseThrow();
    }

    @Test
    void withoutOptionsTheTableIsUnchanged() {
        TableMapping mapping = mapping(Map.of());
        assertThat(mapping.output()).isEqualTo(TABLE);
        assertThat(mapping.mapper(EXPRESSIONS).map(row(1, "Ada", "2.50"))).isEqualTo(row(1, "Ada", "2.50"));
    }

    @Test
    void renamesComputesAddsAndDropsColumnsInSourceOrder() {
        TableMapping mapping = mapping(Map.of(
                "mapping",
                List.of(
                        Map.of("from", "id", "to", "order_id"),
                        Map.of(
                                "from",
                                "amount",
                                "to",
                                "amount_cents",
                                "expr",
                                "amount * 100",
                                "type",
                                "DECIMAL(14, 2)"),
                        Map.of("expr", "UPPER(customerName)", "to", "loud"),
                        Map.of("value", "shop", "to", "_source")),
                "drop",
                List.of("password")));
        CatalogTable out = mapping.output();
        assertThat(names(out))
                .containsExactly("order_id", "customerName", "amount_cents", "created_at", "paid", "loud", "_source");
        assertThat(column(out, "order_id").type()).isEqualTo(DataTypes.INT64().notNull());
        assertThat(column(out, "amount_cents").type()).isEqualTo(DataTypes.DECIMAL(14, 2));
        assertThat(column(out, "loud").type()).isEqualTo(DataTypes.STRING());
        assertThat(column(out, "_source").type()).isEqualTo(DataTypes.STRING().notNull());
        assertThat(column(out, "customerName").comment()).isEqualTo("who ordered");
        assertThat(out.primaryKey()).containsExactly("order_id");

        Row mapped = mapping.mapper(EXPRESSIONS).map(row(7, "ada", "1.25"));
        assertThat(mapped.table()).isEqualTo(Fixtures.ORDERS);
        assertThat(mapped.get(0)).isEqualTo(7L);
        assertThat(mapped.get(1)).isEqualTo("ada");
        assertThat((BigDecimal) mapped.get(2)).isEqualByComparingTo("125.00");
        assertThat(mapped.get(5)).isEqualTo("ADA");
        assertThat(mapped.get(6)).isEqualTo("shop");
        assertThat(mapped.arity()).isEqualTo(7);
    }

    @Test
    void ordersByMappingAndDropsOtherColumns() {
        TableMapping mapping = mapping(Map.of(
                "mapping",
                List.of(Map.of("value", "1", "to", "one", "type", "INT32"), Map.of("from", "amount")),
                "others",
                "drop",
                "order",
                "mapping"));
        assertThat(names(mapping.output())).containsExactly("one", "amount");
        assertThat(mapping.output().primaryKey()).isEmpty();
        assertThat(mapping.mapper(EXPRESSIONS).map(row(1, "a", "3.00")).get(0)).isEqualTo(1);
    }

    @Test
    void writesNamesInTheChosenStyleButKeepsExplicitNames() {
        TableMapping mapping = mapping(Map.of(
                "name-case",
                "snake",
                "mapping",
                List.of(Map.of("from", "paid"), Map.of("from", "id", "to", "orderID"))));
        assertThat(names(mapping.output()))
                .containsExactly("orderID", "customer_name", "amount", "created_at", "paid", "password");
        assertThat(problem(
                        "name-case",
                        () -> TableMapping.derive(
                                Fixtures.config(Map.of("name-case", "upper")),
                                TABLE.withColumns(
                                        List.of(Column.of("a", DataTypes.INT32()), Column.of("A", DataTypes.INT32()))),
                                EXPRESSIONS)))
                .isEqualTo("two output columns of shop.orders are named A");
    }

    @Test
    void appliesTypesNullabilityAndDefaults() {
        TableMapping mapping = mapping(Map.of(
                "mapping",
                List.of(
                        Map.of("from", "amount", "type", "STRING", "default", "n/a", "comment", "as text"),
                        Map.of("from", "customerName", "nullable", "false"),
                        Map.of("from", "created_at", "type", "DATE"))));
        CatalogTable out = mapping.output();
        assertThat(column(out, "amount").type()).isEqualTo(DataTypes.STRING().notNull());
        assertThat(column(out, "amount").defaultValue()).isEqualTo("n/a");
        assertThat(column(out, "amount").comment()).isEqualTo("as text");
        assertThat(column(out, "customerName").type())
                .isEqualTo(DataTypes.STRING(40).notNull());
        assertThat(column(out, "created_at").type()).isEqualTo(DataTypes.DATE());

        TableMapping.Mapper mapper = mapping.mapper(EXPRESSIONS);
        assertThat(mapper.map(row(1, "a", null)).get(2)).isEqualTo("n/a");
        assertThat(mapper.map(row(1, "a", "2.50")).get(2)).isEqualTo("2.50");
        PipeForgeException error =
                catchThrowableOfType(PipeForgeException.class, () -> mapper.map(row(2, null, "1.00")));
        assertThat(error.code()).isEqualTo(ErrorCode.CONSTRAINT_VIOLATION);
        assertThat(error.kind()).isEqualTo(ErrorKind.DATA);
        assertThat(error.params()).containsEntry("column", "customerName").containsEntry("table", "shop.orders");
    }

    @Test
    void reportsUnknownColumnsOrSkipsThem() {
        assertThat(problem(
                        "mapping", () -> mapping(Map.of("mapping", List.of(Map.of("from", "amout", "to", "total"))))))
                .isEqualTo("entry 1 (to: total): shop.orders has no column amout; did you mean 'amount'?");
        assertThat(problem("drop", () -> mapping(Map.of("drop", List.of("pasword")))))
                .isEqualTo("shop.orders has no column pasword; did you mean 'password'?");
        TableMapping lenient = mapping(Map.of(
                "unmapped", "drop", "drop", List.of("salt"), "mapping", List.of(Map.of("from", "nope", "to", "x"))));
        assertThat(lenient.output()).isEqualTo(TABLE);
    }

    @Test
    void checksExpressionsAndConstantsWhilePlanning() {
        PipeForgeException invalid = catchThrowableOfType(
                PipeForgeException.class,
                () -> mapping(Map.of("mapping", List.of(Map.of("expr", "amount + nmae", "to", "x")))));
        assertThat(invalid.code()).isEqualTo(ErrorCode.EXPRESSION_INVALID);
        assertThat(problem(
                        "mapping",
                        () -> mapping(Map.of("mapping", List.of(Map.of("value", "abc", "to", "n", "type", "INT32"))))))
                .startsWith("entry 1 (to: n): value 'abc' does not convert: ");
        assertThat(problem(
                        "mapping",
                        () -> mapping(Map.of(
                                "mapping",
                                List.of(Map.of("from", "amount", "default", "free", "type", "DECIMAL(5, 1)"))))))
                .startsWith("entry 1 (from: amount): default 'free' does not convert: ");
        assertThat(problem("mapping", () -> mapping(Map.of("others", "drop"))))
                .isEqualTo("no column of shop.orders is left");
    }

    @Test
    void followsTheErrorPolicyOfExpressions() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("mapping", List.of(Map.of("expr", "amount / 0", "to", "boom")));
        options.put("on-error", "null");
        assertThat(mapping(options).mapper(EXPRESSIONS).map(row(1, "a", "1.00")).get(6))
                .isNull();

        options.put("on-error", "dirty");
        PipeForgeException dirty = catchThrowableOfType(
                PipeForgeException.class,
                () -> mapping(options).mapper(EXPRESSIONS).map(row(1, "a", "1.00")));
        assertThat(dirty.code()).isEqualTo(ErrorCode.EXPRESSION_ERROR);
        assertThat(dirty.kind()).isEqualTo(ErrorKind.DATA);
    }

    @Test
    void keepsTheKeyOnlyWhenEveryKeyColumnPassesUnchanged() {
        assertThat(mapping(Map.of("mapping", List.of(Map.of("from", "id", "expr", "id + 1"))))
                        .output()
                        .primaryKey())
                .isEmpty();
        assertThat(mapping(Map.of("drop", List.of("id"))).output().primaryKey()).isEmpty();
        assertThat(mapping(Map.of("name-case", "upper")).output().primaryKey()).containsExactly("ID");
    }
}
