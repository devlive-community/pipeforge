/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.row;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.devlive.pipeforge.api.annotation.Stable;
import org.jspecify.annotations.Nullable;

/**
 * Identifies a table. Systems without catalogs or schemas leave those parts {@code null}; a
 * single-table source may use {@link #of(String)}.
 */
@Stable
public record TableId(@Nullable String catalog, @Nullable String database, @Nullable String schema, String table) {

    public TableId {
        Objects.requireNonNull(table, "table");
        if (table.isEmpty()) {
            throw new IllegalArgumentException("Table name must not be empty");
        }
    }

    /** A table without catalog, database or schema. */
    public static TableId of(String table) {
        return new TableId(null, null, null, table);
    }

    /** A table in a database, e.g. MySQL. */
    public static TableId of(String database, String table) {
        return new TableId(null, database, null, table);
    }

    /**
     * Parses a dotted name: {@code t}, {@code db.t}, {@code db.schema.t} or {@code catalog.db.schema.t}.
     */
    public static TableId parse(String name) {
        String[] parts = name.split("\\.", -1);
        return switch (parts.length) {
            case 1 -> of(parts[0]);
            case 2 -> of(parts[0], parts[1]);
            case 3 -> new TableId(null, parts[0], parts[1], parts[2]);
            case 4 -> new TableId(parts[0], parts[1], parts[2], parts[3]);
            default -> throw new IllegalArgumentException("Too many parts in table name: " + name);
        };
    }

    /** The dotted name of the parts that are set, e.g. {@code db.orders}. */
    public String toQualifiedName() {
        List<String> parts = new ArrayList<>(4);
        for (String part : new String[] {catalog, database, schema}) {
            if (part != null) {
                parts.add(part);
            }
        }
        parts.add(table);
        return String.join(".", parts);
    }

    @Override
    public String toString() {
        return toQualifiedName();
    }
}
