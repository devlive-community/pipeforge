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

    /**
     * A checkpoint cuts the stream here: the rows before it are in the checkpoint, those after it
     * are not. Every operator passes it on after the rows it received before it.
     */
    void barrier(org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier barrier);

    /**
     * Every row of a split up to a position came before: passed on to a sink that keeps progress;
     * dropped where rows leave the task, as no sink of this task writes them.
     */
    default void progress(String splitId, String position) {}
}
