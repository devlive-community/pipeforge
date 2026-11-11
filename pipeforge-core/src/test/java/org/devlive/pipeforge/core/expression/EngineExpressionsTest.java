/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.devlive.pipeforge.api.type.DataTypes.BOOLEAN;
import static org.devlive.pipeforge.api.type.DataTypes.DECIMAL;
import static org.devlive.pipeforge.api.type.DataTypes.INT32;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;
import static org.devlive.pipeforge.api.type.DataTypes.TIMESTAMP_LTZ;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.devlive.pipeforge.api.expression.ExpressionInfo;
import org.devlive.pipeforge.api.expression.OnError;
import org.devlive.pipeforge.api.expression.RowEvaluator;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.Errors;
import org.junit.jupiter.api.Test;

class EngineExpressionsTest {

    private static final List<Column> COLUMNS =
            List.of(Column.of("name", STRING(20)), Column.of("amount", DECIMAL(10, 2)), Column.of("qty", INT32()));
    private static final Instant RUN_START = Instant.parse("2026-11-07T00:31:15Z");
    private static final EngineExpressions EXPRESSIONS =
            EngineExpressions.forRun(ZoneId.of("Asia/Shanghai"), RUN_START);

    private static Row row(Object name, Object amount, Object qty) {
        return GenericRow.of(TableId.of("demo", "orders"), name, amount, qty);
    }

    @Test
    void analyzesTypesAndDeterminism() {
        List<ExpressionInfo> infos =
                EXPRESSIONS.analyze(List.of("UPPER(NAME)", "amount * qty", "qty > 1", "NOW()"), COLUMNS);
        assertThat(infos)
                .extracting(ExpressionInfo::text)
                .containsExactly("UPPER(NAME)", "amount * qty", "qty > 1", "NOW()");
        assertThat(infos.get(0).type()).isEqualTo(STRING());
        assertThat(infos.get(1).type()).isEqualTo(DECIMAL(21, 2));
        assertThat(infos.get(2).type()).isEqualTo(BOOLEAN());
        assertThat(infos.get(3).type().root()).isEqualTo(TIMESTAMP_LTZ(3).root());
        assertThat(infos).extracting(ExpressionInfo::deterministic).containsExactly(true, true, true, false);
    }

    @Test
    void reportsInvalidExpressionsWithTheirPosition() {
        PipeForgeException error = Errors.thrown(
                ErrorCode.EXPRESSION_INVALID, () -> EXPRESSIONS.analyze(List.of("name || nmae"), COLUMNS));
        assertThat(error.params()).containsEntry("line", 1).containsEntry("column", 9);
        assertThat(error.localizedMessage("en-US")).contains("did you mean 'name'?");
    }

    @Test
    void compilesAndEvaluatesRows() {
        RowEvaluator evaluator =
                EXPRESSIONS.compile(List.of("UPPER(name)", "amount * qty", "RUN_START_TIME()"), COLUMNS, OnError.FAIL);
        assertThat(evaluator.size()).isEqualTo(3);
        Object[] out = new Object[3];
        evaluator.evaluate(row("pipe", new BigDecimal("1.25"), 4), out);
        assertThat(out[0]).isEqualTo("PIPE");
        assertThat(out[1]).isEqualTo(new BigDecimal("5.00"));
        assertThat(out[2]).isEqualTo(RUN_START);
        evaluator.evaluate(row(null, null, 1), out);
        assertThat(out[0]).isNull();
        assertThat(out[1]).isNull();
    }

    @Test
    void testsConditions() {
        RowEvaluator condition = EXPRESSIONS.compile(List.of("qty > 1"), COLUMNS, OnError.FAIL);
        assertThat(condition.test(row("a", BigDecimal.ONE, 2))).isTrue();
        assertThat(condition.test(row("a", BigDecimal.ONE, 1))).isFalse();
        assertThat(condition.test(row("a", BigDecimal.ONE, null))).isFalse();
    }

    @Test
    void followsTheErrorPolicy() {
        Row zero = row("a", BigDecimal.ONE, 0);
        RowEvaluator asNull = EXPRESSIONS.compile(List.of("amount / qty"), COLUMNS, OnError.NULL);
        Object[] out = new Object[1];
        asNull.evaluate(zero, out);
        assertThat(out[0]).isNull();
        assertThat(asNull.errorCount()).isEqualTo(1);

        RowEvaluator dirty = EXPRESSIONS.compile(List.of("amount / qty"), COLUMNS, OnError.DIRTY);
        PipeForgeException bad = Errors.thrown(ErrorCode.EXPRESSION_ERROR, () -> dirty.evaluate(zero, out));
        assertThat(bad.kind()).isEqualTo(ErrorKind.DATA);

        RowEvaluator failing = EXPRESSIONS.compile(List.of("amount / qty"), COLUMNS, OnError.FAIL);
        PipeForgeException fatal = Errors.thrown(ErrorCode.EXPRESSION_ERROR, () -> failing.evaluate(zero, out));
        assertThat(fatal.kind()).isEqualTo(ErrorKind.FATAL);
    }

    @Test
    void planningUsesTheJobTimeZone() {
        EngineExpressions planning = EngineExpressions.planning(ZoneId.of("UTC"));
        assertThat(planning.context().timeZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(EXPRESSIONS.context().runStartTime()).isEqualTo(RUN_START);
    }
}
