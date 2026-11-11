/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.devlive.pipeforge.expressions.Fixtures.SCHEMA;
import static org.devlive.pipeforge.expressions.Fixtures.SHANGHAI;
import static org.devlive.pipeforge.expressions.Fixtures.eval;
import static org.devlive.pipeforge.expressions.Fixtures.nulls;
import static org.devlive.pipeforge.expressions.Fixtures.row;

import java.util.List;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.expressions.eval.CodeGenerator;
import org.devlive.pipeforge.expressions.function.FunctionDefinition;
import org.devlive.pipeforge.expressions.function.FunctionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodeGenerationTest {

    private final ExpressionEngine engine = new ExpressionEngine(TestFunctions.registry());

    @BeforeEach
    void resetCounters() {
        TestFunctions.TWICE_CALLS.set(0);
        TestFunctions.NOISE_CALLS.set(0);
    }

    private List<Expression> analyze(String... texts) {
        return java.util.Arrays.stream(texts)
                .map(t -> engine.analyze(t, SCHEMA))
                .toList();
    }

    @Test
    void sharedDeterministicSubexpressionsRunOncePerRow() {
        Evaluator evaluator = engine.compile(
                analyze("TWICE(l) + 1", "TWICE(l) * TWICE(l)", "CASE WHEN TWICE(l) > 0 THEN TWICE(l) END"),
                OnError.FAIL,
                SHANGHAI);
        assertThat(evaluator.evaluate(row())).containsExactly(129L, 16384L, 128L);
        assertThat(TestFunctions.TWICE_CALLS.get()).isEqualTo(1);
        evaluator.evaluate(row());
        assertThat(TestFunctions.TWICE_CALLS.get()).isEqualTo(2);
    }

    @Test
    void theInterpreterComputesEveryOccurrence() {
        engine.interpret(analyze("TWICE(l) + TWICE(l)"), OnError.FAIL, SHANGHAI).evaluate(row());
        assertThat(TestFunctions.TWICE_CALLS.get()).isEqualTo(2);
    }

    @Test
    void nonDeterministicCallsAreNeverShared() {
        Expression noise = engine.analyze("NOISE(l) - NOISE(l)", SCHEMA);
        assertThat(noise.deterministic()).isFalse();
        assertThat(engine.analyze("TWICE(l)", SCHEMA).deterministic()).isTrue();
        engine.compile(List.of(noise), OnError.FAIL, SHANGHAI).evaluate(row());
        assertThat(TestFunctions.NOISE_CALLS.get()).isEqualTo(2);
    }

    @Test
    void sharedSubexpressionsAreOnlyComputedWhenNeeded() {
        Evaluator evaluator = engine.compile(
                analyze("CASE WHEN b THEN 0 ELSE TWICE(l) END", "COALESCE(i, TWICE(l))"), OnError.FAIL, SHANGHAI);
        evaluator.evaluate(row());
        assertThat(TestFunctions.TWICE_CALLS.get()).isZero();
        evaluator.evaluate(nulls());
        assertThat(TestFunctions.TWICE_CALLS.get()).isZero();
    }

    @Test
    void aFailedSharedSubexpressionFailsEveryUser() {
        Evaluator evaluator = engine.compile(analyze("l / (i - 32) > 0", "l / (i - 32)"), OnError.NULL, SHANGHAI);
        assertThat(evaluator.evaluate(row())).containsExactly(null, null);
        assertThat(evaluator.errorCount()).isEqualTo(2);
    }

    @Test
    void callsFunctionsWithPrimitivesVarargsAndNulls() {
        assertThat(eval(engine, "TWICE(i)", row())).isEqualTo(64L);
        assertThat(eval(engine, "TWICE(i)", nulls())).isNull();
        assertThat(eval(engine, "test.glue('-', s, s5, 'x')", row())).isEqualTo("hello-abc-x");
        assertThat(eval(engine, "test.glue('-')", row())).isEqualTo("");
        assertThat(eval(engine, "SHOW_VALUE(s)", nulls())).isEqualTo("nothing");
        assertThat(eval(engine, "SHOW_VALUE(s)", row())).isEqualTo("some hello");
        assertThat(eval(engine, "STRICT(-i)", row())).isEqualTo("ERROR: negative values are not allowed");
        assertThat(eval(engine, "FAILS(s)", row())).isEqualTo("ERROR: IllegalStateException: broken for hello");
        assertThat(engine.analyze("SHOW_VALUE(s)", SCHEMA).type()).hasToString("STRING NOT NULL");
        assertThat(engine.analyze("TWICE(id)", SCHEMA).type()).hasToString("INT64");
        assertThat(engine.analyze("TWICE(i)", SCHEMA).type()).hasToString("INT64");
    }

    @Test
    void reportsFunctionsThatDoNotAcceptTheArguments() {
        assertThatThrownBy(() -> engine.analyze("TWICE(s)", SCHEMA))
                .isInstanceOf(PipeForgeException.class)
                .hasMessageContaining("TWICE does not accept (STRING); expected TWICE(INT64)");
        assertThatThrownBy(() -> engine.analyze("test.glue(1)", SCHEMA))
                .hasMessageContaining("expected TEST.GLUE(STRING, STRING, ...)");
        assertThatThrownBy(() -> FunctionRegistry.empty().register(new FunctionDefinition("X", List.of(), true, true)))
                .hasMessageContaining("needs at least one signature");
        assertThatThrownBy(() -> TestFunctions.registry()
                        .register(new FunctionDefinition(
                                "twice",
                                List.of(TestFunctions.signature(
                                        "twice", List.of(DataTypes.INT64()), false, DataTypes.INT64())),
                                true,
                                true)))
                .hasMessageContaining("TWICE is already registered");
        assertThat(engine.functions().find("twice")).isPresent();
        assertThat(engine.functions().all()).hasSize(6);
    }

    @Test
    void reusesTheGeneratedClassForTheSameStructure() {
        Evaluator first = engine.compile(analyze("i + 1"), OnError.FAIL, SHANGHAI);
        Evaluator second = engine.compile(analyze("i + 2"), OnError.FAIL, SHANGHAI);
        assertThat(first.evaluate(row())[0]).isEqualTo(33);
        assertThat(second.evaluate(row())[0]).isEqualTo(34);
        assertThat(first).isNotSameAs(second);
        String source = CodeGenerator.source(
                analyze("TWICE(l) + TWICE(l)").stream().map(Expression::root).toList());
        assertThat(source)
                .containsPattern("private boolean m\\d+;")
                .contains("org.devlive.pipeforge.expressions.TestFunctions.twice(");
    }

    @Test
    void evaluatesSingleExpressionsAndConditions() {
        Evaluator evaluator = engine.compile(analyze("i > 10", "s"), OnError.FAIL, SHANGHAI);
        evaluator.nextRow();
        assertThat(evaluator.evaluate(1, row())).isEqualTo("hello");
        assertThat(evaluator.test(row())).isTrue();
        assertThat(evaluator.test(nulls())).isFalse();
        Object[] out = new Object[2];
        evaluator.evaluate(row(), out);
        assertThat(out).containsExactly(true, "hello");
        assertThat(evaluator.expressions()).hasSize(2);
    }
}
