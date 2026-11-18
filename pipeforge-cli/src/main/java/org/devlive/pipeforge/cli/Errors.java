/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.io.PrintStream;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorInfo;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * Prints errors to the standard error: {@code ERROR PF-CONF-0002 <message>} and the hint on the
 * next line, or with {@code -o json} / {@code -o yaml} the error object of the REST API.
 */
public final class Errors {

    private Errors() {}

    public static void print(Session session, PipeForgeException error) {
        ErrorInfo info = error.toErrorInfo(session.language());
        PrintStream err = session.err();
        if (!session.table()) {
            err.print(Output.render(Map.of("error", info), session.format()));
        } else {
            err.println("ERROR " + info.code() + " " + info.message());
            if (info.hint() != null && !info.hint().isBlank()) {
                err.println("  " + info.hint());
            }
        }
        if (session.verbose() && error.getCause() != null) {
            error.getCause().printStackTrace(err);
        }
        err.flush();
    }

    /** An error the server described, already in the session's language. */
    public static void print(Session session, org.devlive.pipeforge.client.ApiException failure) {
        org.devlive.pipeforge.client.ApiError error = failure.error();
        PrintStream err = session.err();
        if (!session.table()) {
            err.print(Output.render(Map.of("error", error), session.format()));
        } else {
            err.println("ERROR " + error.code() + " " + error.message());
            if (error.hint() != null && !error.hint().isBlank()) {
                err.println("  " + error.hint());
            }
        }
        if (session.verbose() && failure.getCause() != null) {
            failure.getCause().printStackTrace(err);
        }
        err.flush();
    }

    /**
     * The exit code of an error of the server: an unknown object 6, an invalid job 3, a refused
     * identity 7, anything else 1.
     */
    public static ExitCode exit(org.devlive.pipeforge.client.ApiException failure) {
        return switch (failure.status()) {
            case 404 -> ExitCode.NOT_FOUND;
            case 400, 422 -> ExitCode.INVALID;
            case 401, 403 -> ExitCode.UNAUTHORIZED;
            default -> ExitCode.ERROR;
        };
    }

    /** An unexpected exception: a defect, reported as PF-SYS-0001 with the stack trace when verbose. */
    public static void internal(Session session, RuntimeException e) {
        print(
                session,
                PipeForgeException.of(ErrorCode.INTERNAL)
                        .param("detail", e.getClass().getSimpleName() + ": " + e.getMessage())
                        .cause(e)
                        .build());
        if (session.verbose()) {
            e.printStackTrace(session.err());
        }
    }
}
