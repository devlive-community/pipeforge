/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.Objects;

/**
 * Cumulative counters of one operator in one task.
 *
 * @param node the node id
 * @param rowsIn rows the operator received; for a source, the rows it read
 * @param rowsOut rows the operator emitted; for a sink, the rows its writer accepted
 * @param rowsFiltered input rows for which a transform emitted nothing
 * @param rowsExpanded extra rows when a transform emitted several rows for one input row
 * @param selfTimeNanos time spent in the operator's own code, excluding the operators after it
 * @param rowsDirty bad rows the operator skipped: they went into it and not out
 */
public record OperatorSnapshot(
        String node,
        long rowsIn,
        long rowsOut,
        long rowsFiltered,
        long rowsExpanded,
        long selfTimeNanos,
        long rowsDirty) {

    public OperatorSnapshot {
        Objects.requireNonNull(node, "node");
    }

    /** Counters of an operator that skipped no bad rows. */
    public OperatorSnapshot(
            String node, long rowsIn, long rowsOut, long rowsFiltered, long rowsExpanded, long selfTimeNanos) {
        this(node, rowsIn, rowsOut, rowsFiltered, rowsExpanded, selfTimeNanos, 0);
    }
}
