/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.row.TableId;
import org.jspecify.annotations.Nullable;

/** The structure of a table: columns, primary key, partition keys, options and comment. */
@Stable
public record CatalogTable(
        TableId id,
        List<Column> columns,
        List<String> primaryKey,
        List<String> partitionKeys,
        Map<String, String> options,
        @Nullable String comment) {

    public CatalogTable {
        Objects.requireNonNull(id, "id");
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        partitionKeys = List.copyOf(partitionKeys);
        options = Map.copyOf(options);
        Set<String> names = new HashSet<>();
        for (Column column : columns) {
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("Duplicate column '" + column.name() + "' in " + id);
            }
        }
        for (String key : primaryKey) {
            if (!names.contains(key)) {
                throw new IllegalArgumentException("Primary key column '" + key + "' does not exist in " + id);
            }
        }
        for (String key : partitionKeys) {
            if (!names.contains(key)) {
                throw new IllegalArgumentException("Partition column '" + key + "' does not exist in " + id);
            }
        }
    }

    /** A table with the given columns and nothing else. */
    public static CatalogTable of(TableId id, List<Column> columns) {
        return new CatalogTable(id, columns, List.of(), List.of(), Map.of(), null);
    }

    public CatalogTable withPrimaryKey(String... keys) {
        return new CatalogTable(id, columns, List.of(keys), partitionKeys, options, comment);
    }

    public CatalogTable withColumns(List<Column> value) {
        List<String> keys = new ArrayList<>(primaryKey);
        keys.removeIf(k -> value.stream().noneMatch(c -> c.name().equals(k)));
        List<String> partitions = new ArrayList<>(partitionKeys);
        partitions.removeIf(k -> value.stream().noneMatch(c -> c.name().equals(k)));
        return new CatalogTable(id, value, keys, partitions, options, comment);
    }

    public CatalogTable withId(TableId value) {
        return new CatalogTable(value, columns, primaryKey, partitionKeys, options, comment);
    }

    public Optional<Column> column(String name) {
        return columns.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    /** Position of a column, or -1. */
    public int indexOf(String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    public List<String> columnNames() {
        return columns.stream().map(Column::name).toList();
    }
}
