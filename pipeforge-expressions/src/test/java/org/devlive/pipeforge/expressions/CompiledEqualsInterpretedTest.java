/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.devlive.pipeforge.expressions.Fixtures.SCHEMA;
import static org.devlive.pipeforge.expressions.Fixtures.SHANGHAI;
import static org.devlive.pipeforge.expressions.Fixtures.TABLE;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

/**
 * Property test: for random well-typed expressions over random rows, the compiled code and the
 * interpreter produce the same value or fail with the same reason. All expressions of a batch are
 * compiled into one class, so sharing of subexpressions between them is exercised as well.
 */
class CompiledEqualsInterpretedTest {

    private static final long SEED = Long.getLong("expressions.seed", 20261110L);
    private static final int BATCHES = 40;
    private static final int EXPRESSIONS_PER_BATCH = 12;
    private static final int ROWS = 25;

    private final Random random = new Random(SEED);
    private final ExpressionEngine engine = new ExpressionEngine(TestFunctions.registry());

    @Test
    void compiledAndInterpretedEvaluationAgree() {
        int compared = 0;
        int failures = 0;
        for (int batch = 0; batch < BATCHES; batch++) {
            List<Expression> expressions = new ArrayList<>();
            while (expressions.size() < EXPRESSIONS_PER_BATCH) {
                String text =
                        switch (random.nextInt(4)) {
                            case 0 -> integer(4);
                            case 1 -> decimal(4);
                            case 2 -> string(4);
                            default -> condition(4);
                        };
                expressions.add(engine.analyze(text, SCHEMA));
            }
            Evaluator compiled = engine.compile(expressions, OnError.NULL, SHANGHAI);
            Evaluator interpreted = engine.interpret(expressions, OnError.NULL, SHANGHAI);
            Evaluator compiledStrict = engine.compile(expressions, OnError.FAIL, SHANGHAI);
            Evaluator interpretedStrict = engine.interpret(expressions, OnError.FAIL, SHANGHAI);
            for (int r = 0; r < ROWS; r++) {
                Row row = randomRow();
                Object[] a = compiled.evaluate(row);
                Object[] b = interpreted.evaluate(row);
                for (int i = 0; i < a.length; i++) {
                    assertThat(Fixtures.sameResult(a[i], b[i]))
                            .as(
                                    "seed %d: %s on %s: compiled %s, interpreted %s",
                                    SEED, expressions.get(i).text(), row, Fixtures.show(a[i]), Fixtures.show(b[i]))
                            .isTrue();
                    compared++;
                    String x = strict(compiledStrict, i, row);
                    String y = strict(interpretedStrict, i, row);
                    assertThat(x)
                            .as("seed %d: %s on %s", SEED, expressions.get(i).text(), row)
                            .isEqualTo(y);
                    if (x.startsWith("ERROR")) {
                        failures++;
                    }
                }
            }
            assertThat(compiled.errorCount()).isEqualTo(interpreted.errorCount());
        }
        assertThat(compared).isEqualTo(BATCHES * EXPRESSIONS_PER_BATCH * ROWS);
        // The generator must exercise the error paths too, but not only them.
        assertThat(failures).isPositive().isLessThan(compared / 2);
    }

    private static String strict(Evaluator evaluator, int index, Row row) {
        try {
            evaluator.nextRow();
            return Fixtures.show(evaluator.evaluate(index, row));
        } catch (PipeForgeException e) {
            return "ERROR " + e.params().get("reason");
        }
    }

    // ------------------------------------------------------------------ generators

    private <T> T pick(List<T> options) {
        return options.get(random.nextInt(options.size()));
    }

    private String integer(int depth) {
        if (depth == 0 || random.nextInt(4) == 0) {
            return pick(
                    List.of("i", "l", "i8", "i16", "id", "0", "1", "-7", "2147483647", "addr.zip", "arr[2]", "m['a']"));
        }
        return switch (random.nextInt(8)) {
            case 0 -> "(" + integer(depth - 1) + " + " + integer(depth - 1) + ")";
            case 1 -> "(" + integer(depth - 1) + " - " + integer(depth - 1) + ")";
            case 2 -> "(" + integer(depth - 1) + " * " + integer(depth - 1) + ")";
            case 3 -> "(" + integer(depth - 1) + " % " + integer(depth - 1) + ")";
            case 4 -> "CASE WHEN " + condition(depth - 1) + " THEN " + integer(depth - 1) + " ELSE "
                    + integer(depth - 1) + " END";
            case 5 -> "COALESCE(" + integer(depth - 1) + ", " + integer(depth - 1) + ")";
            case 6 -> "TWICE(" + integer(depth - 1) + ")";
            default -> "TRY_CAST(" + string(depth - 1) + " AS BIGINT)";
        };
    }

    private String decimal(int depth) {
        if (depth == 0 || random.nextInt(4) == 0) {
            return pick(List.of("d", "1.5", "0.01", "CAST(i AS DECIMAL(12, 3))", "x", "f"));
        }
        return switch (random.nextInt(6)) {
            case 0 -> "(" + decimal(depth - 1) + " + " + integer(depth - 1) + ")";
            case 1 -> "(" + decimal(depth - 1) + " * " + decimal(depth - 1) + ")";
            case 2 -> "(" + numeric(depth - 1) + " / " + numeric(depth - 1) + ")";
            case 3 -> "-(" + decimal(depth - 1) + ")";
            case 4 -> "NULLIF(" + decimal(depth - 1) + ", 0)";
            default -> "CAST(" + integer(depth - 1) + " AS DECIMAL(20, 2))";
        };
    }

    private String numeric(int depth) {
        return random.nextBoolean() ? integer(depth) : decimal(depth);
    }

    private String string(int depth) {
        if (depth == 0 || random.nextInt(4) == 0) {
            return pick(List.of("s", "s5", "'abc'", "''", "'42'", "addr.city", "$table", "name"));
        }
        return switch (random.nextInt(6)) {
            case 0 -> "(" + string(depth - 1) + " || " + string(depth - 1) + ")";
            case 1 -> "CAST(" + numeric(depth - 1) + " AS STRING)";
            case 2 -> "CASE WHEN " + condition(depth - 1) + " THEN " + string(depth - 1) + " END";
            case 3 -> "IFNULL(" + string(depth - 1) + ", 'none')";
            case 4 -> "IF(" + condition(depth - 1) + ", " + string(depth - 1) + ", " + string(depth - 1) + ")";
            default -> "CAST(" + string(depth - 1) + " AS VARCHAR(3))";
        };
    }

    private String condition(int depth) {
        if (depth == 0 || random.nextInt(5) == 0) {
            return pick(List.of("b", "TRUE", "FALSE", "CAST(NULL AS BOOLEAN)", "s IS NULL", "dt < ts"));
        }
        String op = pick(List.of("=", "<>", "<", "<=", ">", ">=", "IS DISTINCT FROM"));
        return switch (random.nextInt(9)) {
            case 0 -> "(" + numeric(depth - 1) + " " + op + " " + numeric(depth - 1) + ")";
            case 1 -> "(" + string(depth - 1) + " " + op + " " + string(depth - 1) + ")";
            case 2 -> "(" + condition(depth - 1) + " AND " + condition(depth - 1) + ")";
            case 3 -> "(" + condition(depth - 1) + " OR " + condition(depth - 1) + ")";
            case 4 -> "NOT " + condition(depth - 1);
            case 5 -> integer(depth - 1) + " IN (1, 32, NULL, " + integer(depth - 1) + ")";
            case 6 -> numeric(depth - 1) + " BETWEEN " + numeric(depth - 1) + " AND " + numeric(depth - 1);
            case 7 -> string(depth - 1) + pick(List.of(" LIKE 'a%'", " LIKE '%_'", " RLIKE '[0-9]'", " NOT LIKE ''"));
            default -> "(" + condition(depth - 1) + ") IS " + pick(List.of("TRUE", "NOT FALSE", "NULL"));
        };
    }

    private Row randomRow() {
        Object[] values = new Object[SCHEMA.columns().size()];
        values[0] = maybe(() -> (byte) pick(List.of(0, 1, -1, 8, 127)).intValue());
        values[1] = maybe(() -> (short) pick(List.of(0, 16, -300, 32767)).intValue());
        values[2] = maybe(() -> pick(List.of(0, 1, 32, -5, Integer.MAX_VALUE, 1_000_000)));
        values[3] = maybe(() -> pick(List.of(0L, 64L, -9L, Long.MAX_VALUE, 3_000_000_000L)));
        values[4] = maybe(() -> new BigDecimal(pick(List.of("0.00", "12.50", "-3.33", "99999999.99"))));
        values[6] = maybe(() -> pick(List.of(0f, 1.5f, -2.25f)));
        values[7] = maybe(() -> pick(List.of(0.0, 2.25, -1e10, 1e300)));
        values[8] = maybe(() -> pick(List.of("", "abc", "42", "hello world", "7")));
        values[9] = maybe(() -> pick(List.of("", "ab", "12345")));
        values[10] = maybe(random::nextBoolean);
        values[11] = maybe(() -> LocalDate.of(2026, 1 + random.nextInt(12), 1 + random.nextInt(28)));
        values[12] = maybe(() -> LocalDateTime.of(2026, 1 + random.nextInt(12), 1, random.nextInt(24), 0));
        values[16] = maybe(() -> List.of(1, 2));
        values[17] = maybe(() -> java.util.Map.of("a", 5L));
        values[18] = maybe(() -> GenericRow.of(TABLE, pick(List.of("Berlin", "")), pick(List.of(0, 10115))));
        values[19] = maybe(() -> "Upper");
        values[20] = maybe(() -> "lower");
        values[21] = pick(List.of(1L, -1L, 0L, Long.MIN_VALUE));
        return GenericRow.of(TABLE, values);
    }

    private Object maybe(java.util.function.Supplier<?> supplier) {
        return random.nextInt(5) == 0 ? null : supplier.get();
    }
}
