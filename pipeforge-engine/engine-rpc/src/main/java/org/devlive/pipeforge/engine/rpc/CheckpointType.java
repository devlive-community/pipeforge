/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

/** A checkpoint the engine takes on its own, or a savepoint someone asked for. */
public enum CheckpointType {
    CHECKPOINT,
    /** Kept until deleted by hand; a run can be started from it after an upgrade. */
    SAVEPOINT
}
