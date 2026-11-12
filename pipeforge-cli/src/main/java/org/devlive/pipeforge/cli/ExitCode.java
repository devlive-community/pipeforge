/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

/** The exit codes of the pipeforge command; they never change their meaning. */
public enum ExitCode {
    SUCCESS(0),
    ERROR(1),
    USAGE(2),
    INVALID(3),
    RUN_FAILED(4),
    TIMEOUT(5),
    NOT_FOUND(6),
    UNAUTHORIZED(7),
    CANCELLED(8),
    NOT_RECONCILED(9);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** The key of its description in the message bundle. */
    String messageKey() {
        return "exit." + code;
    }
}
