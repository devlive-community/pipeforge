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

    /** The same table with other rows. */
    public TestTable withRows(String newName, List<Row> newRows) {
        return new TestTable(newName, table, newRows);
    }
}
