/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

/**
 * The status of a job in the engine. The names follow the run state machine of the control plane;
 * states that only the control plane knows, such as {@code QUEUE} or {@code RETRY_WAIT}, are not
 * reported by engines.
 */
public enum JobStatus {
    /** Accepted; tasks are being deployed. */
    CREATED,
    /** Tasks are running. */
    RUNNING,
    /** A cancel was requested; tasks are stopping. */
    CANCELLING,
    /** Every source ended and every sink wrote and committed its data. */
    SUCCESS,
    /** A task or the job master failed; the result carries the error. */
    FAILED,
    /** Stopped on request. */
    CANCELLED;

    /** Whether the job has ended. */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
