/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.expression;

import org.devlive.pipeforge.api.annotation.Evolving;
import org.devlive.pipeforge.api.row.Row;
import org.jspecify.annotations.Nullable;

/**
 * Evaluates compiled expressions row by row. Not thread-safe: use one per parallel instance.
 *
 * <p>Values have the Java representation of their logical type, as in {@link Row}.
 */
@Evolving
public interface RowEvaluator {

    /** The number of compiled expressions. */
    int size();

    /** Evaluates every expression for {@code row} into {@code out}, which has at least {@link #size()} slots. */
    void evaluate(Row row, @Nullable Object[] out);

    /** Evaluates a single boolean condition (the first expression); NULL counts as not satisfied. */
    boolean test(Row row);

    /** Failures that became NULL because of {@link OnError#NULL}. */
    long errorCount();
}
