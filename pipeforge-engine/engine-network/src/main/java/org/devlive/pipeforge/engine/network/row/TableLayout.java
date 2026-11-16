/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.row;

import java.util.List;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;

/**
 * A table of the table directory of an operator chain: its number in the job and the current
 * version of its columns. Rows carry only the number; their columns are read with this layout.
 */
public record TableLayout(int id, TableId table, List<DataType.Field> columns) {

    public TableLayout {
        if (id < 0) {
            throw new IllegalArgumentException("table id must not be negative: " + id);
        }
        columns = List.copyOf(columns);
    }
}
