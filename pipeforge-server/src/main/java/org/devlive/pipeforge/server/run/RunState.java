/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

/** The state of a run, as the master reports it (protocol: RunState). */
public enum RunState {
    /** Accepted; waiting for slots. */
    QUEUE,
    RUNNING,
    SUCCESS,
    FAILED,
    /** Cancel was asked for; the tasks are stopping. */
    CANCELLING,
    CANCELLED,
    /** The run took longer than its timeout. */
    TIMEOUT;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED || this == TIMEOUT;
    }

    /** The state of the protocol's {@code RunState}. */
    static RunState fromProto(org.devlive.pipeforge.engine.rpc.proto.RunState state) {
        return switch (state) {
            case RUN_STATE_QUEUE -> QUEUE;
            case RUN_STATE_RUNNING -> RUNNING;
            case RUN_STATE_SUCCESS -> SUCCESS;
            case RUN_STATE_FAILED -> FAILED;
            case RUN_STATE_CANCELLING -> CANCELLING;
            case RUN_STATE_CANCELLED -> CANCELLED;
            case RUN_STATE_TIMEOUT -> TIMEOUT;
            default -> throw new IllegalArgumentException("unknown run state " + state);
        };
    }
}
