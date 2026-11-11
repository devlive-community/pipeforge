/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.expression;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.expression.ExpressionInfo;
import org.devlive.pipeforge.api.expression.OnError;
import org.devlive.pipeforge.api.expression.RowEvaluator;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.expressions.EvaluationContext;
import org.devlive.pipeforge.expressions.Evaluator;
import org.devlive.pipeforge.expressions.Expression;
import org.devlive.pipeforge.expressions.ExpressionEngine;
import org.devlive.pipeforge.expressions.InputSchema;
import org.jspecify.annotations.Nullable;

/**
 * The expression language as components see it ({@link ExpressionCompiler}), backed by the
 * expression engine with the built-in functions.
 *
 * <p>The evaluation context fixes the job time zone, the start time of the run ({@code
 * RUN_START_TIME()}, the same in every task of the run) and the clock of {@code NOW()}.
 */
public final class EngineExpressions implements ExpressionCompiler {

    private static final class Shared {
        static final ExpressionEngine ENGINE = new ExpressionEngine();
    }

    private final ExpressionEngine engine;
    private final EvaluationContext context;

    public EngineExpressions(ExpressionEngine engine, EvaluationContext context) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** For planning: types are checked; the run starts now. */
    public static EngineExpressions planning(ZoneId timeZone) {
        return new EngineExpressions(Shared.ENGINE, EvaluationContext.of(timeZone));
    }

    /** For a run that started at {@code runStartTime}, with the system clock. */
    public static EngineExpressions forRun(ZoneId timeZone, Instant runStartTime) {
        return new EngineExpressions(Shared.ENGINE, new EvaluationContext(timeZone, runStartTime, Clock.systemUTC()));
    }

    public EvaluationContext context() {
        return context;
    }

    @Override
    public List<ExpressionInfo> analyze(List<String> expressions, List<Column> columns) {
        InputSchema schema = InputSchema.of(columns);
        return expressions.stream()
                .map(text -> engine.analyze(text, schema))
                .map(e -> new ExpressionInfo(e.text(), e.type(), e.deterministic()))
                .toList();
    }

    @Override
    public RowEvaluator compile(List<String> expressions, List<Column> columns, OnError onError) {
        InputSchema schema = InputSchema.of(columns);
        List<Expression> analyzed =
                expressions.stream().map(text -> engine.analyze(text, schema)).toList();
        return new CompiledEvaluator(engine.compile(analyzed, policy(onError), context));
    }

    private static org.devlive.pipeforge.expressions.OnError policy(OnError onError) {
        return switch (onError) {
            case DIRTY -> org.devlive.pipeforge.expressions.OnError.DIRTY;
            case NULL -> org.devlive.pipeforge.expressions.OnError.NULL;
            case FAIL -> org.devlive.pipeforge.expressions.OnError.FAIL;
        };
    }

    private static final class CompiledEvaluator implements RowEvaluator {
        private final Evaluator evaluator;

        CompiledEvaluator(Evaluator evaluator) {
            this.evaluator = evaluator;
        }

        @Override
        public int size() {
            return evaluator.expressions().size();
        }

        @Override
        public void evaluate(Row row, @Nullable Object[] out) {
            evaluator.evaluate(row, out);
        }

        @Override
        public boolean test(Row row) {
            return evaluator.test(row);
        }

        @Override
        public long errorCount() {
            return evaluator.errorCount();
        }
    }
}
