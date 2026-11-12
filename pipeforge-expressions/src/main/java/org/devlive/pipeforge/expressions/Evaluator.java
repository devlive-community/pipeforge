/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import java.util.List;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.expressions.eval.FunctionInstances;
import org.devlive.pipeforge.expressions.eval.RowFunctions;
import org.devlive.pipeforge.expressions.runtime.ExprError;

/**
 * Evaluates the expressions of one operator over rows. Not thread-safe: each parallel instance
 * creates its own evaluator.
 *
 * <p>Run-time failures follow {@link OnError}: {@code NULL} yields NULL and counts the error,
 * {@code DIRTY} and {@code FAIL} throw {@link ErrorCode#EXPRESSION_ERROR} of kind DATA or FATAL.
 *
 * <p>An evaluator owns the opened objects of the instance functions it calls ({@link
 * org.devlive.pipeforge.api.function.FunctionLifecycle}); {@link #close()} closes them.
 */
public final class Evaluator implements AutoCloseable {

    private final List<Expression> expressions;
    private final RowFunctions functions;
    private final OnError onError;
    private final FunctionInstances instances;
    private long errors;

    Evaluator(List<Expression> expressions, RowFunctions functions, OnError onError, FunctionInstances instances) {
        this.expressions = List.copyOf(expressions);
        this.functions = functions;
        this.onError = onError;
        this.instances = instances;
    }

    public List<Expression> expressions() {
        return expressions;
    }

    /** Evaluates every expression for a row. */
    public Object[] evaluate(Row row) {
        Object[] out = new Object[expressions.size()];
        evaluate(row, out);
        return out;
    }

    /** Evaluates every expression for a row into {@code out}. */
    public void evaluate(Row row, Object[] out) {
        functions.reset();
        for (int i = 0; i < out.length; i++) {
            out[i] = evaluate(i, row, false);
        }
    }

    /** Evaluates one expression of a row (call {@link #nextRow()} first when sharing work between them). */
    public Object evaluate(int index, Row row) {
        return evaluate(index, row, false);
    }

    /** Starts a new row: forgets the shared results of the previous one. */
    public void nextRow() {
        functions.reset();
    }

    /** Evaluates a single boolean condition; NULL counts as not satisfied. */
    public boolean test(Row row) {
        functions.reset();
        return Boolean.TRUE.equals(evaluate(0, row, true));
    }

    private Object evaluate(int index, Row row, boolean condition) {
        try {
            return functions.eval(index, row);
        } catch (ExprError e) {
            return failed(index, e.getMessage(), e, condition);
        } catch (PipeForgeException e) {
            throw e;
        } catch (RuntimeException e) {
            return failed(index, e.getClass().getSimpleName() + ": " + e.getMessage(), e, condition);
        }
    }

    private Object failed(int index, String reason, RuntimeException cause, boolean condition) {
        if (onError == OnError.NULL) {
            errors++;
            return condition ? Boolean.FALSE : null;
        }
        throw PipeForgeException.of(ErrorCode.EXPRESSION_ERROR)
                .param("expression", expressions.get(index).text())
                .param("reason", reason)
                .kind(onError == OnError.FAIL ? ErrorKind.FATAL : ErrorKind.DATA)
                .cause(cause)
                .build();
    }

    /** Failures turned into NULL because of {@link OnError#NULL}. */
    public long errorCount() {
        return errors;
    }

    /**
     * Closes the objects of the instance functions; does nothing when every function is static.
     *
     * @throws PipeForgeException {@link ErrorCode#PLUGIN_CALL_FAILED} when one fails to close
     */
    @Override
    public void close() {
        instances.close();
    }
}
