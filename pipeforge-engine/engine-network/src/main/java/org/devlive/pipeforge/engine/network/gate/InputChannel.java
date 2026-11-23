/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.gate;

import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.jspecify.annotations.Nullable;

/**
 * One upstream subpartition of an input. A channel may hold as many data buffers as it has
 * credit: its own {@code buffers-per-channel} and the floating ones it borrowed from the gate
 * because the producer reported a backlog.
 */
public abstract sealed class InputChannel permits LocalInputChannel, RemoteInputChannel {

    final InputGate gate;
    final int index;
    final ResultPartitionId partition;
    final int subpartition;
    private final int exclusive;
    private int floating;
    private int inUse;
    private int backlog;
    // guarded by the gate's lock
    boolean queued;
    boolean ended;
    /** Not read while a checkpoint aligns: it delivered its barrier (protocol, section 3.3). */
    boolean blocked;
    /** Had a buffer while it was blocked; queued again when it is unblocked. */
    boolean parked;

    InputChannel(InputGate gate, int index, ResultPartitionId partition, int subpartition, int exclusive) {
        this.gate = gate;
        this.index = index;
        this.partition = partition;
        this.subpartition = subpartition;
        this.exclusive = exclusive;
    }

    public ResultPartitionId partition() {
        return partition;
    }

    public int subpartition() {
        return subpartition;
    }

    /** Starts reading the upstream subpartition. */
    abstract void request();

    /** The next buffer, or {@code null} when none is there; called by the gate. */
    abstract @Nullable Buffer poll();

    /** Whether a buffer may be there; the gate polls again. */
    abstract boolean hasMore();

    /** Stops reading: the producer is told when it is on another worker. */
    abstract void release();

    /** Data buffers this channel may take now. */
    final synchronized int credit() {
        return exclusive + floating - inUse;
    }

    final int initialCredit() {
        return exclusive;
    }

    /** A data buffer arrived (remote) or was taken (local). */
    final synchronized void took() {
        inUse++;
    }

    /**
     * The consumer is done with a data buffer.
     *
     * @return the credit to announce: 0 when a floating buffer goes back to the gate
     */
    final int recycled() {
        boolean giveBack;
        synchronized (this) {
            inUse--;
            giveBack = floating > 0 && backlog == 0;
            if (giveBack) {
                floating--;
            }
        }
        if (giveBack) {
            gate.returnFloating();
            return 0;
        }
        return 1;
    }

    /**
     * The producer has {@code value} data buffers waiting: borrow floating buffers to take them.
     *
     * @return the new credit to announce
     */
    final int backlog(int value) {
        int need;
        synchronized (this) {
            backlog = value;
            need = value - (exclusive + floating - inUse);
        }
        if (need <= 0) {
            return 0;
        }
        int borrowed = gate.borrowFloating(need);
        synchronized (this) {
            floating += borrowed;
        }
        return borrowed;
    }

    final synchronized int floatingHeld() {
        return floating;
    }

    /** Gives back the floating buffers when the channel ends or is released. */
    final void returnAllFloating() {
        int held;
        synchronized (this) {
            held = floating;
            floating = 0;
        }
        for (int i = 0; i < held; i++) {
            gate.returnFloating();
        }
    }
}
