/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.table;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.row.TableId;

/**
 * A change to the structure of a table. Schema changes flow through the pipeline in order with
 * the rows; transforms map them the same way they map rows.
 */
@Stable
public sealed interface SchemaChangeEvent
        permits CreateTable,
                DropTable,
                TruncateTable,
                RenameTable,
                AddColumn,
                DropColumn,
                RenameColumn,
                AlterColumnType,
                AlterColumnComment {

    /** The table the change applies to (before the change, for renames). */
    TableId table();
}
