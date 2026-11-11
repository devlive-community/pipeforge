/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.expression;

import java.util.List;
import org.devlive.pipeforge.api.annotation.Evolving;
import org.devlive.pipeforge.api.table.Column;

/**
 * The expression language of PipeForge for components: the same parser, type rules and function
 * library as filter conditions, the expression playground and SQL. Components get it from {@link
 * org.devlive.pipeforge.api.factory.FactoryContext#expressions()} while planning and from {@link
 * org.devlive.pipeforge.api.transform.TransformContext#expressions()} at run time.
 *
 * <p>Errors are reported with registered error codes: an expression that does not parse or does not
 * type-check fails with {@code PF-PLAN-0007} and the position of the problem, before any data is
 * read; a failure while evaluating follows the {@link OnError} given to {@link #compile}.
 */
@Evolving
public interface ExpressionCompiler {

    /**
     * Parses and type-checks expressions over the given columns. Unquoted column names match
     * case-insensitively, names in backticks exactly.
     *
     * @return the result type and determinism of each expression, in order
     */
    List<ExpressionInfo> analyze(List<String> expressions, List<Column> columns);

    /**
     * Compiles expressions over the given columns for evaluation; subexpressions they share are
     * evaluated once per row. Each parallel instance compiles its own evaluator.
     */
    RowEvaluator compile(List<String> expressions, List<Column> columns, OnError onError);
}
