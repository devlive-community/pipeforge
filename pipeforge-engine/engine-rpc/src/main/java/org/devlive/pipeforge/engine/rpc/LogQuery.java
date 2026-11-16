/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.Locale;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * What to read from the log of a task.
 *
 * @param offset where to start in the log; a negative value counts back from its end, and reading
 *     then starts at the next whole line
 * @param maxBytes how much of the log one chunk covers at most; 0 for {@link #DEFAULT_BYTES}, more
 *     than {@link #MAX_BYTES} is cut to it
 * @param contains keep only the entries that contain this text; empty for all
 * @param minLevel keep only the entries of this level or above ({@code TRACE}, {@code DEBUG},
 *     {@code INFO}, {@code WARN}, {@code ERROR}); {@code null} for all
 * @param follow keep sending what the task writes until it ends
 */
public record LogQuery(long offset, long maxBytes, String contains, @Nullable String minLevel, boolean follow) {

    public static final long DEFAULT_BYTES = 64 * 1024;
    public static final long MAX_BYTES = 4 * 1024 * 1024;

    /** The levels in order, lowest first. */
    public static final java.util.List<String> LEVELS = java.util.List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    public LogQuery {
        contains = contains == null ? "" : contains;
        if (maxBytes < 0) {
            throw PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                    .param("reason", "max_bytes must not be negative, not " + maxBytes)
                    .build();
        }
        maxBytes = maxBytes == 0 ? DEFAULT_BYTES : Math.min(maxBytes, MAX_BYTES);
        if (minLevel != null && minLevel.isBlank()) {
            minLevel = null;
        }
        if (minLevel != null) {
            String level = minLevel.trim().toUpperCase(Locale.ROOT);
            if (!LEVELS.contains(level)) {
                throw PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                        .param(
                                "reason",
                                "min_level must be one of " + String.join(", ", LEVELS) + ", not '" + minLevel + "'")
                        .build();
            }
            minLevel = level;
        }
    }

    /** The whole log from its start, one chunk. */
    public static LogQuery all() {
        return new LogQuery(0, 0, "", null, false);
    }

    /** Whether the query drops entries. */
    public boolean filters() {
        return !contains.isEmpty() || minLevel != null;
    }
}
