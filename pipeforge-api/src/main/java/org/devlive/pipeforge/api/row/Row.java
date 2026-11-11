/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.row;

import org.devlive.pipeforge.api.annotation.Stable;
import org.jspecify.annotations.Nullable;

/**
 * One row flowing through a job. Field values use the Java classes of their {@link
 * org.devlive.pipeforge.api.type.DataType}; {@code null} represents SQL NULL.
 */
@Stable
public interface Row {

    RowKind kind();

    /** The table the row belongs to; multi-table sources route by it. */
    TableId table();

    /** Number of fields. */
    int arity();

    @Nullable
    Object get(int pos);

    default boolean isNullAt(int pos) {
        return get(pos) == null;
    }

    /** A row with the same table and values and another kind. */
    Row withKind(RowKind kind);
}
