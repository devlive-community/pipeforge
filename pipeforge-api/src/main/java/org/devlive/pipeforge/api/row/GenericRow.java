/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.row;

import java.util.Arrays;
import java.util.Objects;
import org.devlive.pipeforge.api.annotation.Stable;
import org.jspecify.annotations.Nullable;

/**
 * Array-based {@link Row}. Fields are mutable through {@link #set(int, Object)} so that readers can
 * reuse instances; rows handed to a collector must not be changed afterwards.
 */
@Stable
public final class GenericRow implements Row {

    private final TableId table;
    private final @Nullable Object[] fields;
    private RowKind kind;

    public GenericRow(TableId table, RowKind kind, int arity) {
        this(table, kind, new Object[arity]);
    }

    private GenericRow(TableId table, RowKind kind, @Nullable Object[] fields) {
        this.table = Objects.requireNonNull(table, "table");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.fields = fields;
    }

    /** An {@code INSERT} row with the given values (copied). */
    public static GenericRow of(TableId table, @Nullable Object... values) {
        return new GenericRow(table, RowKind.INSERT, values.clone());
    }

    /** A row of the given kind with the given values (copied). */
    public static GenericRow ofKind(TableId table, RowKind kind, @Nullable Object... values) {
        return new GenericRow(table, kind, values.clone());
    }

    /** A copy of any row as a {@code GenericRow}. */
    public static GenericRow copyOf(Row row) {
        GenericRow copy = new GenericRow(row.table(), row.kind(), row.arity());
        for (int i = 0; i < row.arity(); i++) {
            copy.fields[i] = row.get(i);
        }
        return copy;
    }

    @Override
    public RowKind kind() {
        return kind;
    }

    public void setKind(RowKind value) {
        this.kind = Objects.requireNonNull(value, "kind");
    }

    @Override
    public TableId table() {
        return table;
    }

    @Override
    public int arity() {
        return fields.length;
    }

    @Override
    public @Nullable Object get(int pos) {
        return fields[pos];
    }

    public void set(int pos, @Nullable Object value) {
        fields[pos] = value;
    }

    @Override
    public GenericRow withKind(RowKind value) {
        return new GenericRow(table, value, fields.clone());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GenericRow row
                && row.kind == kind
                && row.table.equals(table)
                && Arrays.deepEquals(row.fields, fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, table, Arrays.deepHashCode(fields));
    }

    @Override
    public String toString() {
        return kind.shortName()
                + table
                + Arrays.deepToString(fields).replace('[', '(').replace(']', ')');
    }
}
