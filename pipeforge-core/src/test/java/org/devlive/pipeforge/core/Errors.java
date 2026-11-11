/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/** Assertions on {@link PipeForgeException}s. */
public final class Errors {

    private Errors() {}

    /** The exception thrown by {@code call}, which must be a {@link PipeForgeException} with {@code code}. */
    public static PipeForgeException thrown(ErrorCode code, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(PipeForgeException.class);
        PipeForgeException error = (PipeForgeException) thrown;
        assertThat(error.code()).as(error.getMessage()).isEqualTo(code);
        return error;
    }

    /** The English message of the exception thrown by {@code call}. */
    public static String message(ErrorCode code, ThrowingCallable call) {
        return thrown(code, call).localizedMessage("en-US");
    }
}
