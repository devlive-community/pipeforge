/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.Objects;

/**
 * The metrics of one node, summed over its parallel instances.
 *
 * @param node the node id
 * @param kind {@code source}, {@code transform} or {@code sink}
 * @param rowsIn rows the node received; for a source, the rows it read
 * @param rowsOut rows the node emitted; for a sink, the rows it wrote
 * @param rowsFiltered rows a transform dropped
 * @param rowsExpanded extra rows a transform emitted when one row became several
 * @param selfTimeRatio share of the running time spent in the node's own code (0 to 1), averaged
 *     over its instances; -1 when unknown
 * @param bytesIn bytes of rows the node received from other tasks, when it reads from the network
 * @param bytesOut bytes of rows the node sent to other tasks, when it writes to the network
 * @param rowsDirty bad rows the node skipped
 */
public record NodeMetrics(
        String node,
        String kind,
        long rowsIn,
        long rowsOut,
        long rowsFiltered,
        long rowsExpanded,
        double selfTimeRatio,
        long bytesIn,
        long bytesOut,
        long rowsDirty) {

    public NodeMetrics {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(kind, "kind");
    }

    /** Metrics of a node that skipped no bad rows. */
    public NodeMetrics(
            String node,
            String kind,
            long rowsIn,
            long rowsOut,
            long rowsFiltered,
            long rowsExpanded,
            double selfTimeRatio,
            long bytesIn,
            long bytesOut) {
        this(node, kind, rowsIn, rowsOut, rowsFiltered, rowsExpanded, selfTimeRatio, bytesIn, bytesOut, 0);
    }

    /** Output rows per input row; -1 when the node received nothing. */
    public double selectivity() {
        return rowsIn == 0 ? -1 : (double) rowsOut / rowsIn;
    }
}
