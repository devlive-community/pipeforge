/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.partition;

import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.jspecify.annotations.Nullable;

/** How the one consumer of a subpartition reads it: buffers in order, data only with credit. */
public interface SubpartitionView {

    /** A buffer and the number of data buffers still waiting behind it. */
    record Delivery(Buffer buffer, int backlog, boolean last) {}

    /**
     * The next finished buffer, or {@code null}.
     *
     * @param data whether a data buffer may be taken; an event is always taken
     */
    @Nullable
    Delivery poll(boolean data);

    /** Data buffers waiting to be taken. */
    int backlog();

    /** Stops reading: the buffers not taken are dropped. */
    void release();

    /** The output this view reads. */
    ResultPartition partition();
}
