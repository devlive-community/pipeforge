/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import java.util.List;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.jspecify.annotations.Nullable;

/**
 * One table of the fake source.
 *
 * @param table the schema
 * @param rows the rows to generate; the size of {@code data} when it is given
 * @param data the literal rows, or {@code null} to generate them
 */
record FakeTable(CatalogTable table, long rows, @Nullable List<Row> data) {

    FakeTable {
        data = data == null ? null : List.copyOf(data);
    }
}
