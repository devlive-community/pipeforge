/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.eval;

import java.time.ZoneId;
import org.devlive.pipeforge.expressions.EvaluationContext;

/** Superclass of the classes {@link CodeGenerator} generates; holds constants and the context. */
public abstract class GeneratedBase implements RowFunctions {

    /** Constants referenced by the generated code: literal values, types, function metadata. */
    protected final Object[] c;

    protected final ZoneId zone;

    /** The evaluation context, passed to functions that declare it. */
    protected final EvaluationContext ctx;

    private final int size;

    protected GeneratedBase(Object[] constants, EvaluationContext context, int size) {
        this.c = constants;
        this.ctx = context;
        this.zone = context.timeZone();
        this.size = size;
    }

    @Override
    public int size() {
        return size;
    }
}
