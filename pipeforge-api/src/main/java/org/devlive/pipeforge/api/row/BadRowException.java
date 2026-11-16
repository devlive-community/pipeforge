/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.row;

import org.devlive.pipeforge.api.annotation.Stable;
import org.jspecify.annotations.Nullable;

/**
 * Thrown by a transform or a sink writer for the row it was given, by a reader for a record it
 * cannot read, when the problem is the data and not the plugin: a value that does not convert, a
 * constraint the target refuses, a malformed message. The engine handles the row with the dirty
 * data policy of the job: it fails the job, or skips the row and counts it. Any other exception
 * fails the job.
 */
@Stable
public class BadRowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param reason what is wrong with the row, in words a user understands */
    public BadRowException(String reason) {
        super(reason);
    }

    public BadRowException(String reason, @Nullable Throwable cause) {
        super(reason, cause);
    }
}
