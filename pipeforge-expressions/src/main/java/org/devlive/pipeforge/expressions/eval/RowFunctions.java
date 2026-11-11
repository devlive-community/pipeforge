/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.eval;

import org.devlive.pipeforge.api.row.Row;

/**
 * Evaluates a fixed list of expressions over rows. {@link #reset()} is called once per row before
 * the expressions of that row are evaluated, so that shared subexpressions are computed once.
 */
public interface RowFunctions {

    void reset();

    /** Evaluates expression {@code index}; failures throw {@code ExprError} or other runtime exceptions. */
    Object eval(int index, Row in);

    int size();
}
