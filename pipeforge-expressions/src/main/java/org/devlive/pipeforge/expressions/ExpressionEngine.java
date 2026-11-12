/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import java.util.List;
import org.devlive.pipeforge.expressions.analysis.Analyzer;
import org.devlive.pipeforge.expressions.ast.Node;
import org.devlive.pipeforge.expressions.eval.CodeGenerator;
import org.devlive.pipeforge.expressions.eval.FunctionInstances;
import org.devlive.pipeforge.expressions.eval.Interpreter;
import org.devlive.pipeforge.expressions.function.FunctionRegistry;

/**
 * Entry point of the expression language: analyze expressions while planning (types and errors
 * are known before the job runs), then compile them for execution.
 *
 * <pre>{@code
 * ExpressionEngine engine = new ExpressionEngine(registry);
 * Expression total = engine.analyze("amount * qty", InputSchema.of(table));
 * Evaluator evaluator = engine.compile(List.of(total), OnError.DIRTY, context);
 * Object value = evaluator.evaluate(row)[0];
 * }</pre>
 */
public final class ExpressionEngine {

    private final FunctionRegistry functions;

    /** An engine with the built-in function library. */
    public ExpressionEngine() {
        this(org.devlive.pipeforge.expressions.function.builtin.BuiltinFunctions.registry());
    }

    public ExpressionEngine(FunctionRegistry functions) {
        this.functions = functions;
    }

    public FunctionRegistry functions() {
        return functions;
    }

    /**
     * Parses and type-checks an expression.
     *
     * @throws org.devlive.pipeforge.common.error.PipeForgeException {@code PF-PLAN-0007} with the position of the problem
     */
    public Expression analyze(String text, InputSchema schema) {
        return new Expression(text, Analyzer.analyze(text, schema, functions));
    }

    /**
     * Compiles expressions to bytecode; shared subexpressions are evaluated once per row. The
     * objects of instance functions are created and opened now; close the evaluator to close them.
     *
     * @throws org.devlive.pipeforge.common.error.PipeForgeException {@code PF-PLUG-0007} when one cannot be opened
     */
    public Evaluator compile(List<Expression> expressions, OnError onError, EvaluationContext context) {
        List<Node> roots = expressions.stream().map(Expression::root).toList();
        FunctionInstances instances = FunctionInstances.open(roots, context);
        try {
            return new Evaluator(expressions, CodeGenerator.compile(roots, context, instances), onError, instances);
        } catch (RuntimeException e) {
            instances.close();
            throw e;
        }
    }

    /** Evaluates expressions by walking their trees (the reference semantics of {@link #compile}). */
    public Evaluator interpret(List<Expression> expressions, OnError onError, EvaluationContext context) {
        List<Node> roots = expressions.stream().map(Expression::root).toList();
        FunctionInstances instances = FunctionInstances.open(roots, context);
        return new Evaluator(expressions, new Interpreter(roots, context, instances), onError, instances);
    }
}
