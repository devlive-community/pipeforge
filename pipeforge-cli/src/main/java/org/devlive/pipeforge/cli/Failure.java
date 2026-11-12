/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * Ends a command with a coded error and an exit code. {@link Cli} prints the error to the standard
 * error, localized, with its hint.
 */
public final class Failure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient PipeForgeException error;
    private final ExitCode exit;

    public Failure(PipeForgeException error, ExitCode exit) {
        super(error.getMessage(), error);
        this.error = error;
        this.exit = exit;
    }

    public PipeForgeException error() {
        return error;
    }

    public ExitCode exit() {
        return exit;
    }
}
