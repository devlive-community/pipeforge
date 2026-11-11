/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.RowKind;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.expressions.function.FunctionRegistry;
import org.devlive.pipeforge.expressions.runtime.R;

/** Schema, rows and helpers shared by the expression tests. */
final class Fixtures {

    static final TableId TABLE = new TableId(null, "shop", "public", "orders");

    static final InputSchema SCHEMA = InputSchema.of(List.of(
            Column.of("i8", DataTypes.INT8()),
            Column.of("i16", DataTypes.INT16()),
            Column.of("i", DataTypes.INT32()),
            Column.of("l", DataTypes.INT64()),
            Column.of("d", DataTypes.DECIMAL(10, 2)),
            Column.of("big", DataTypes.DECIMAL(38, 10)),
            Column.of("f", DataTypes.FLOAT32()),
            Column.of("x", DataTypes.FLOAT64()),
            Column.of("s", DataTypes.STRING()),
            Column.of("s5", DataTypes.STRING(5)),
            Column.of("b", DataTypes.BOOLEAN()),
            Column.of("dt", DataTypes.DATE()),
            Column.of("ts", DataTypes.TIMESTAMP(3)),
            Column.of("ltz", DataTypes.TIMESTAMP_LTZ(3)),
            Column.of("tm", DataTypes.TIME()),
            Column.of("bin", DataTypes.BYTES()),
            Column.of("arr", DataTypes.ARRAY(DataTypes.INT32())),
            Column.of("m", DataTypes.MAP(DataTypes.STRING(), DataTypes.INT64())),
            Column.of(
                    "addr",
                    DataTypes.ROW(
                            DataTypes.FIELD("city", DataTypes.STRING()), DataTypes.FIELD("zip", DataTypes.INT32()))),
            Column.of("Name", DataTypes.STRING()),
            Column.of("name", DataTypes.STRING()),
            Column.of("id", DataTypes.INT64().notNull())));

    static final EvaluationContext SHANGHAI = new EvaluationContext(ZoneId.of("Asia/Shanghai"));

    /** A row with typical values. */
    static Row row() {
        return GenericRow.ofKind(
                TABLE,
                RowKind.UPDATE_AFTER,
                (byte) 8,
                (short) 16,
                32,
                64L,
                new BigDecimal("12.50"),
                new BigDecimal("1.0000000000"),
                1.5f,
                2.25,
                "hello",
                "abc",
                true,
                LocalDate.of(2026, 11, 7),
                LocalDateTime.of(2026, 11, 7, 12, 30, 0, 123_000_000),
                Instant.parse("2026-11-07T04:30:00Z"),
                LocalTime.of(12, 30),
                new byte[] {104, 105},
                List.of(10, 20, 30),
                Map.of("a", 1L, "b", 2L),
                GenericRow.of(TableId.of("addr"), "Berlin", 10115),
                "Upper",
                "lower",
                7L);
    }

    /** A row where every nullable column is NULL. */
    static Row nulls() {
        Object[] values = new Object[SCHEMA.columns().size()];
        values[values.length - 1] = 1L;
        return GenericRow.of(TABLE, values);
    }

    static final ExpressionEngine ENGINE = new ExpressionEngine(FunctionRegistry.empty());

    /**
     * Evaluates an expression interpreted and compiled, asserts both agree, and returns the value,
     * or "ERROR: reason" when it fails.
     */
    static Object eval(ExpressionEngine engine, String text, Row row) {
        Expression expression = engine.analyze(text, SCHEMA);
        Object interpreted = run(engine.interpret(List.of(expression), OnError.FAIL, SHANGHAI), row);
        Object compiled = run(engine.compile(List.of(expression), OnError.FAIL, SHANGHAI), row);
        assertThat(sameResult(interpreted, compiled))
                .as("interpreted %s and compiled %s agree for %s", show(interpreted), show(compiled), text)
                .isTrue();
        return compiled;
    }

    static Object eval(String text, Row row) {
        return eval(ENGINE, text, row);
    }

    static Object eval(String text) {
        return eval(text, row());
    }

    private static Object run(Evaluator evaluator, Row row) {
        try {
            return evaluator.evaluate(row)[0];
        } catch (PipeForgeException e) {
            return "ERROR: " + e.params().get("reason");
        }
    }

    static boolean sameResult(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.getClass() != b.getClass()) {
            return false;
        }
        return a instanceof String ? a.equals(b) : R.same(a, b);
    }

    static String show(Object value) {
        return value instanceof byte[] bytes ? Arrays.toString(bytes) : Objects.toString(value);
    }
}
