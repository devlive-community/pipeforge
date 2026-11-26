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
    TIMEOUT,
    /** An attempt failed; the next starts after the retry backoff ({@code env.retry}). */
    RETRY_WAIT,
    /** A pause was asked for: the sources stopped and the savepoint is being taken. */
    PAUSING,
    /** Stopped at a savepoint, holding no slots; resuming goes on from it. */
    PAUSED;

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
            case RUN_STATE_PAUSING -> PAUSING;
            case RUN_STATE_PAUSED -> PAUSED;
                // a region restarts inside the attempt: the run goes on
            case RUN_STATE_RESTARTING -> RUNNING;
            default -> throw new IllegalArgumentException("unknown run state " + state);
        };
    }
}
