/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.data;

import java.util.List;
import java.util.Objects;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.type.DataType;

/**
 * A table with its rows: what a source under test must read, or what a sink under test must write.
 *
 * @param name a short name that identifies the data set in test names and messages
 * @param table the schema
 * @param rows the rows, in no particular order
 */
public record TestTable(String name, CatalogTable table, List<Row> rows) {

    public TestTable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(table, "table");
        rows = List.copyOf(rows);
    }

    /**
     * The same table without the rows that have a non-null value {@code holds} rejects for the type
     * of its column.
     */
    public TestTable keeping(java.util.function.BiPredicate<DataType, Object> holds) {
        List<Row> kept = rows.stream()
                .filter(row -> {
                    for (int i = 0; i < row.arity(); i++) {
                        Object value = row.get(i);
                        if (value != null && !holds.test(table.columns().get(i).type(), value)) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();
        return kept.size() == rows.size() ? this : new TestTable(name, table, kept);
    }

    /** The same table with other rows. */
    public TestTable withRows(String newName, List<Row> newRows) {
        return new TestTable(newName, table, newRows);
    }
}
