/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.concurrent.atomic.LongAdder;
import org.devlive.pipeforge.engine.rpc.OperatorSnapshot;

/**
 * Row counters of one operator. Updated on the hot path with one {@link LongAdder#increment()} per
 * row and counter, read by the metrics reporter from another thread.
 */
final class OperatorMetrics {

    private final String node;
    final LongAdder rowsIn = new LongAdder();
    final LongAdder rowsOut = new LongAdder();
    final LongAdder rowsFiltered = new LongAdder();
    final LongAdder rowsExpanded = new LongAdder();

    OperatorMetrics(String node) {
        this.node = node;
    }

    String node() {
        return node;
    }

    OperatorSnapshot snapshot(long selfTimeNanos) {
        return new OperatorSnapshot(
                node, rowsIn.sum(), rowsOut.sum(), rowsFiltered.sum(), rowsExpanded.sum(), selfTimeNanos);
    }
}
