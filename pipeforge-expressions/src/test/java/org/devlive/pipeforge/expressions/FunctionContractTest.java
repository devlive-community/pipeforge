/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.expressions.function.FunctionProvider;
import org.devlive.pipeforge.expressions.function.FunctionRegistry;
import org.devlive.pipeforge.expressions.function.builtin.BuiltinFunctions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Functions declared with the annotations of pipeforge-api, compiled and interpreted. */
class FunctionContractTest {

    static final FunctionProvider ACME = new FunctionProvider(
            "acme-functions@1.0.0",
            "acme",
            List.of(
                    SampleFunctions.Cards.class,
                    SampleFunctions.Dictionary.class,
                    SampleFunctions.FailsToOpen.class,
                    SampleFunctions.FailsToClose.class,
                    SampleFunctions.FailsToConstruct.class));

    static final ExpressionEngine ENGINE =
            new ExpressionEngine(FunctionRegistry.of(List.of(BuiltinFunctions.PROVIDER, ACME)));

    static final EvaluationContext CONTEXT = Fixtures.SHANGHAI
            .withJob(new JobContext("orders-sync", 4, ZoneId.of("UTC")))
            .withParameters(Map.of("region", "eu", "prefix", "k:"));

    @BeforeEach
    void clearEvents() {
        SampleFunctions.EVENTS.clear();
    }

    private static Object evaluate(BiFunction<List<Expression>, EvaluationContext, Evaluator> mode, String text) {
        Expression expression = ENGINE.analyze(text, Fixtures.SCHEMA);
        try (Evaluator evaluator = mode.apply(List.of(expression), CONTEXT)) {
            return evaluator.evaluate(Fixtures.row())[0];
        }
    }

    private static BiFunction<List<Expression>, EvaluationContext, Evaluator> mode(boolean compiled) {
        return compiled
                ? (expressions, context) -> ENGINE.compile(expressions, OnError.FAIL, context)
                : (expressions, context) -> ENGINE.interpret(expressions, OnError.FAIL, context);
    }

    @ParameterizedTest(name = "compiled = {0}")
    @ValueSource(booleans = {true, false})
    void staticFunctionsAreCalledInTheirNamespace(boolean compiled) {
        assertThat(evaluate(mode(compiled), "acme.mask_card(s)")).isEqualTo("****ello");
        assertThat(evaluate(mode(compiled), "ACME.MASK_CARD('4111111111111111')"))
                .isEqualTo("****1111");
        assertThat(evaluate(mode(compiled), "acme.mask_card(CAST(NULL AS STRING))"))
                .isNull();
    }

    @ParameterizedTest(name = "compiled = {0}")
    @ValueSource(booleans = {true, false})
    void declaredTypesDriveCastsAndResults(boolean compiled) {
        assertThat(ENGINE.analyze("acme.with_rate(d, i)", Fixtures.SCHEMA).type())
                .isEqualTo(DataTypes.DECIMAL(18, 2));
        assertThat(evaluate(mode(compiled), "acme.with_rate(d, i)")).isEqualTo(new BigDecimal("400.00"));
        // A BigDecimal parameter without @Param accepts any DECIMAL as it is, like the built-ins
        // (reference/expression-syntax documents CEIL on a DECIMAL(10, 2)).
        assertThat(ENGINE.analyze("acme.whole(d)", Fixtures.SCHEMA).type()).isEqualTo(DataTypes.DECIMAL(9, 0));
        assertThat(ENGINE.analyze("CEIL(d)", Fixtures.SCHEMA).type()).isEqualTo(DataTypes.DECIMAL(9, 0));
        assertThat(evaluate(mode(compiled), "acme.whole(d)")).isEqualTo(new BigDecimal("12"));
        assertThat(ENGINE.analyze("acme.second_of(l, s)", Fixtures.SCHEMA).type())
                .isEqualTo(DataTypes.STRING());
        assertThat(evaluate(mode(compiled), "acme.second_of(l, s)")).isEqualTo("hello");
    }

    @ParameterizedTest(name = "compiled = {0}")
    @ValueSource(booleans = {true, false})
    void parametersDeclaredWithParamTakeExactlyThatType(boolean compiled) {
        // INT32 widens to DECIMAL(16, 2); DECIMAL(38, 10) does not fit and is rejected while planning.
        assertThat(evaluate(mode(compiled), "acme.with_rate(i, i)")).isEqualTo(new BigDecimal("1024.00"));
        assertThatThrownBy(() -> ENGINE.analyze("acme.with_rate(big, i)", Fixtures.SCHEMA))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.EXPRESSION_INVALID);
                    assertThat(e.getMessage())
                            .contains("ACME.WITH_RATE does not accept (DECIMAL(38, 10), INT32)")
                            .contains("expected ACME.WITH_RATE(DECIMAL(16, 2), INT32)");
                });
    }

    @ParameterizedTest(name = "compiled = {0}")
    @ValueSource(booleans = {true, false})
    void theContextIsInjected(boolean compiled) {
        assertThat(evaluate(mode(compiled), "acme.where_am_i('!')")).isEqualTo("Asia/Shanghai orders-sync eu !");
    }

    @ParameterizedTest(name = "compiled = {0}")
    @ValueSource(booleans = {true, false})
    void instanceFunctionsShareOneOpenedObjectPerEvaluator(boolean compiled) {
        List<Expression> expressions = List.of(
                ENGINE.analyze("acme.lookup(s)", Fixtures.SCHEMA),
                ENGINE.analyze("acme.lookup_or_none(CAST(NULL AS STRING))", Fixtures.SCHEMA),
                ENGINE.analyze("acme.lookup(name)", Fixtures.SCHEMA));
        try (Evaluator first = mode(compiled).apply(expressions, CONTEXT);
                Evaluator second = mode(compiled).apply(expressions.subList(0, 1), CONTEXT)) {
            assertThat(first.evaluate(Fixtures.row())).containsExactly("k:hello", "none", "k:lower");
            assertThat(second.evaluate(Fixtures.row())).containsExactly("k:hello");
            assertThat(SampleFunctions.EVENTS).containsExactly("open dictionary", "open dictionary");
        }
        assertThat(SampleFunctions.EVENTS)
                .containsExactly("open dictionary", "open dictionary", "close dictionary", "close dictionary");
    }

    @ParameterizedTest(name = "compiled = {0}")
    @ValueSource(booleans = {true, false})
    void aFailingOpenClosesWhatWasOpened(boolean compiled) {
        List<Expression> expressions = List.of(
                ENGINE.analyze("acme.lookup(s)", Fixtures.SCHEMA), ENGINE.analyze("acme.broken(s)", Fixtures.SCHEMA));

        assertThatThrownBy(() -> mode(compiled).apply(expressions, CONTEXT))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PLUGIN_CALL_FAILED);
                    assertThat(e.params())
                            .containsEntry("plugin", SampleFunctions.FailsToOpen.class.getName())
                            .containsEntry("operation", "FunctionLifecycle.open")
                            .containsEntry("reason", "IllegalStateException: dictionary file is missing");
                });
        assertThat(SampleFunctions.EVENTS)
                .containsExactly("open dictionary", "open failing", "close failing", "close dictionary");
    }

    @Test
    void aFailingConstructorIsReported() {
        Expression expression = ENGINE.analyze("acme.unlicensed(s)", Fixtures.SCHEMA);
        assertThatThrownBy(() -> ENGINE.compile(List.of(expression), OnError.FAIL, CONTEXT))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.params())
                        .containsEntry("operation", "FunctionLifecycle.constructor")
                        .containsEntry("reason", "IllegalStateException: no license"));
    }

    @Test
    void aFailingCloseIsReportedAfterEveryObjectIsClosed() {
        List<Expression> expressions = List.of(
                ENGINE.analyze("acme.lookup(s)", Fixtures.SCHEMA), ENGINE.analyze("acme.sticky(s)", Fixtures.SCHEMA));
        Evaluator evaluator = ENGINE.compile(expressions, OnError.FAIL, CONTEXT);
        assertThat(evaluator.evaluate(Fixtures.row())).containsExactly("k:hello", "hello");

        assertThatThrownBy(evaluator::close)
                .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.params())
                        .containsEntry("operation", "FunctionLifecycle.close")
                        .containsEntry("reason", "IOException: connection reset"));
        assertThat(SampleFunctions.EVENTS).containsExactly("open dictionary", "close sticky", "close dictionary");
    }

    @Test
    void staticFunctionsNeedNoInstances() {
        try (Evaluator evaluator = ENGINE.compile(
                List.of(ENGINE.analyze("UPPER(acme.mask_card(s))", Fixtures.SCHEMA)), OnError.FAIL, CONTEXT)) {
            assertThat(evaluator.evaluate(Fixtures.row())).containsExactly("****ELLO");
        }
        assertThat(SampleFunctions.EVENTS).isEmpty();
    }

    @Test
    void invalidDeclarationsAreRejected() {
        assertThat(rejection(SampleFunctions.InstanceWithoutLifecycle.class))
                .contains("must be static, or")
                .contains("must implement FunctionLifecycle");
        assertThat(rejection(SampleFunctions.PrimitiveNullTolerant.class))
                .contains("nullOnNull = false")
                .contains("needs a reference type instead of int");
        assertThat(rejection(SampleFunctions.BadReturns.class))
                .contains("Invalid @Returns(\"$3\")")
                .contains("use $n with n below 1");
        assertThat(rejection(SampleFunctions.BadParam.class)).contains("Invalid logical type 'DECIMAL(oops)'");
        assertThat(rejection(SampleFunctions.Qualified.class))
                .contains("must be declared without a namespace; it is registered in acme");
        assertThat(rejection(SampleFunctions.Hidden.class)).contains("carries @ScalarFunction but is not public");
    }

    private static String rejection(Class<?> type) {
        FunctionProvider provider = new FunctionProvider("broken@1.0.0", "acme", List.of(type));
        try {
            FunctionRegistry.of(List.of(provider));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        throw new AssertionError(type.getSimpleName() + " was accepted");
    }

    @Test
    void namespacesAreValidatedAndCannotCollide() {
        assertThatThrownBy(() -> new FunctionProvider("x", "Acme", List.of()))
                .hasMessageContaining("Function namespace 'Acme' of x must match");
        FunctionProvider again = new FunctionProvider("acme-copy@1.0.0", "acme", List.of(SampleFunctions.Cards.class));
        assertThatThrownBy(() -> FunctionRegistry.of(List.of(ACME, again)))
                .hasMessage("Function ACME.MASK_CARD is already registered");
        assertThat(ENGINE.functions().find("acme.Lookup")).isPresent();
        assertThat(ENGINE.functions().find("lookup")).isEmpty();
    }

    @Test
    void contextsOutsideAJobHaveDefaults() {
        EvaluationContext context = EvaluationContext.utc();
        assertThat(context.job().jobName()).isEqualTo(EvaluationContext.NO_JOB);
        assertThat(context.job().timeZone()).isEqualTo(context.timeZone());
        assertThat(context.parameters()).isEmpty();
        assertThat(CONTEXT.runStartTime()).isEqualTo(Fixtures.SHANGHAI.runStartTime());
    }
}
