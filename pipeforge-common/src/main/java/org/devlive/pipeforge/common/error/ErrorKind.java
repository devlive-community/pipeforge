/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

/** How the engine treats an error: retry it, route the row as dirty data, or fail. */
public enum ErrorKind {
    /** Temporary condition (network, resources); the operation may succeed when retried. */
    TRANSIENT,
    /** A single row or record is bad; handled by the dirty data policy. */
    DATA,
    /** Retrying cannot help; the run fails. */
    FATAL
}
