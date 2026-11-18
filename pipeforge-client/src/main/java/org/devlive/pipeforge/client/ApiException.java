/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

/**
 * The server refused a request or could not be reached: the HTTP status (0 when no answer came)
 * and the error the server described, with its registered code.
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient ApiError error;

    public ApiException(int status, ApiError error) {
        super(error.message());
        this.status = status;
        this.error = error;
    }

    public ApiException(int status, ApiError error, Throwable cause) {
        super(error.message(), cause);
        this.status = status;
        this.error = error;
    }

    /** The HTTP status, or 0 when the server did not answer. */
    public int status() {
        return status;
    }

    /** The registered error code, e.g. {@code PF-CONF-0010}. */
    public String code() {
        return error.code();
    }

    public ApiError error() {
        return error;
    }
}
