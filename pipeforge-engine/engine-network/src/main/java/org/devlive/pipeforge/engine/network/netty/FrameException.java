/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.netty;

import org.devlive.pipeforge.common.error.PipeForgeException;

/** A frame that breaks the protocol; the connection is closed with an error response. */
final class FrameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient PipeForgeException error;

    FrameException(PipeForgeException error) {
        super(error.getMessage(), error);
        this.error = error;
    }

    PipeForgeException error() {
        return error;
    }
}
