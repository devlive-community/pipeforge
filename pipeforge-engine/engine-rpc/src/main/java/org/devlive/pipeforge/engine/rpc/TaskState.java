/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

/** The state a task reports to its job master. */
public enum TaskState {
    /** The operators are being created and opened. */
    DEPLOYING,
    RUNNING,
    /** The input ended and every operator finished; the report carries the sink's committables. */
    FINISHED,
    /** An operator failed; the report carries the error. */
    FAILED,
    /** Stopped on request. */
    CANCELED;

    public boolean isTerminal() {
        return this == FINISHED || this == FAILED || this == CANCELED;
    }
}
