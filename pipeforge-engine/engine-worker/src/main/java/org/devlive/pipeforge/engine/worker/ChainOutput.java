/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;

/** The next operator of a chain: rows and schema changes travel down it in the same order. */
interface ChainOutput {

    void row(Row row);

    void schemaChange(SchemaChangeEvent event);
}
