/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.table;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.row.TableId;

/** Drops a table. */
@Stable
public record DropTable(TableId table) implements SchemaChangeEvent {}
