/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.table;

import java.util.Map;
import java.util.Objects;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.type.DataType;
import org.jspecify.annotations.Nullable;

/** A column of a {@link CatalogTable}. */
@Stable
public record Column(
        String name,
        DataType type,
        @Nullable String comment,
        @Nullable Object defaultValue,
        Map<String, String> options) {

    public Column {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Column name must not be empty");
        }
        options = Map.copyOf(options);
    }

    /** A column without comment, default value or options. */
    public static Column of(String name, DataType type) {
        return new Column(name, type, null, null, Map.of());
    }

    public Column withType(DataType value) {
        return new Column(name, value, comment, defaultValue, options);
    }

    public Column withName(String value) {
        return new Column(value, type, comment, defaultValue, options);
    }

    public Column withComment(@Nullable String value) {
        return new Column(name, type, value, defaultValue, options);
    }
}
