/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.eval;

import java.time.ZoneId;

/** Superclass of the classes {@link CodeGenerator} generates; holds constants and the time zone. */
public abstract class GeneratedBase implements RowFunctions {

    /** Constants referenced by the generated code: literal values, types, function metadata. */
    protected final Object[] c;

    protected final ZoneId zone;

    private final int size;

    protected GeneratedBase(Object[] constants, ZoneId zone, int size) {
        this.c = constants;
        this.zone = zone;
        this.size = size;
    }

    @Override
    public int size() {
        return size;
    }
}
