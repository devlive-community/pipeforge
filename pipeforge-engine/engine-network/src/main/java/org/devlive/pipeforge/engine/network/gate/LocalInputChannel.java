/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.gate;

import java.util.concurrent.ScheduledExecutorService;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.devlive.pipeforge.engine.network.partition.ResultPartitionManager;
import org.devlive.pipeforge.engine.network.partition.SubpartitionView;
import org.jspecify.annotations.Nullable;

/**
 * A channel to a subpartition on the same worker: buffers are handed over without copying, with
 * the same credit and the same order of events as over the network.
 */
public final class LocalInputChannel extends InputChannel {

    private final ResultPartitionManager partitions;
    private final ScheduledExecutorService timer;
    private volatile @Nullable SubpartitionView view;
    private volatile boolean released;

    LocalInputChannel(
            InputGate gate,
            int index,
            ResultPartitionId partition,
            int subpartition,
            int exclusive,
            ResultPartitionManager partitions,
            ScheduledExecutorService timer) {
        super(gate, index, partition, subpartition, exclusive);
        this.partitions = partitions;
        this.timer = timer;
    }

    // the callback handles the outcome; the future itself has nothing more to report
    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    void request() {
        partitions.partition(partition, timer).whenComplete((output, error) -> {
            if (error != null) {
                gate.fail(error instanceof PipeForgeException e ? e : PipeForgeException.wrap(error));
                return;
            }
            try {
                SubpartitionView created = output.createView(subpartition, () -> gate.available(this));
                view = created;
                if (released) {
                    created.release();
                }
            } catch (RuntimeException e) {
                gate.fail(PipeForgeException.wrap(e));
            }
        });
    }

    @Override
    @Nullable
    Buffer poll() {
        SubpartitionView current = view;
        if (current == null) {
            return null;
        }
        if (credit() <= 0) {
            // out of credit: borrow floating buffers for what waits behind the buffers taken
            backlog(current.backlog());
        }
        SubpartitionView.Delivery delivery = current.poll(credit() > 0);
        if (delivery == null) {
            return null;
        }
        Buffer buffer = delivery.buffer();
        backlog(delivery.backlog());
        if (buffer.isEvent()) {
            return buffer;
        }
        took();
        return buffer.onRecycle(() -> {
            recycled();
            gate.available(this);
        });
    }

    @Override
    boolean hasMore() {
        return view != null;
    }

    @Override
    void release() {
        released = true;
        SubpartitionView current = view;
        if (current != null) {
            current.release();
        }
        returnAllFloating();
    }
}
