/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.devlive.pipeforge.expressions.Fixtures.ENGINE;
import static org.devlive.pipeforge.expressions.Fixtures.SCHEMA;
import static org.devlive.pipeforge.expressions.Fixtures.SHANGHAI;
import static org.devlive.pipeforge.expressions.Fixtures.row;

import java.util.List;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class OnErrorTest {

    private static final List<Expression> DIVIDE = List.of(ENGINE.analyze("l / (i - 32)", SCHEMA));

    @Test
    void dirtyMarksTheRowAsBad() {
        assertThatThrownBy(() -> ENGINE.compile(DIVIDE, OnError.DIRTY, SHANGHAI).evaluate(row()))
                .isInstanceOf(PipeForgeException.class)
                .hasMessage("PF-DATA-0006 Expression 'l / (i - 32)' failed: division by zero")
                .satisfies(e -> {
                    PipeForgeException error = (PipeForgeException) e;
                    assertThat(error.code()).isEqualTo(ErrorCode.EXPRESSION_ERROR);
                    assertThat(error.kind()).isEqualTo(ErrorKind.DATA);
                });
    }

    @Test
    void failStopsTheJob() {
        assertThatThrownBy(
                        () -> ENGINE.interpret(DIVIDE, OnError.FAIL, SHANGHAI).evaluate(row()))
                .satisfies(e -> assertThat(((PipeForgeException) e).kind()).isEqualTo(ErrorKind.FATAL));
    }

    @Test
    void nullYieldsNullAndCountsTheError() {
        Evaluator evaluator = ENGINE.compile(DIVIDE, OnError.NULL, SHANGHAI);
        assertThat(evaluator.evaluate(row())).containsExactly((Object) null);
        assertThat(evaluator.evaluate(row())).containsExactly((Object) null);
        assertThat(evaluator.errorCount()).isEqualTo(2);
        Evaluator condition =
                ENGINE.compile(List.of(ENGINE.analyze("l / (i - 32) > 0", SCHEMA)), OnError.NULL, SHANGHAI);
        assertThat(condition.test(row())).isFalse();
    }

    @Test
    void parsesTheOption() {
        assertThat(OnError.parse(" null ")).isEqualTo(OnError.NULL);
        assertThat(OnError.parse("Dirty")).isEqualTo(OnError.DIRTY);
        assertThatThrownBy(() -> OnError.parse("skip")).isInstanceOf(IllegalArgumentException.class);
    }
}
