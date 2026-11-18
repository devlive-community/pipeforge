/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import org.jspecify.annotations.Nullable;

/**
 * Which part of which task log to read.
 *
 * @param vertex the vertex of the task, as in {@link RunTask#vertex()}
 * @param subtask the subtask
 * @param attempt the attempt
 * @param offset where to start; negative counts back from the end
 * @param maxBytes the most to read; 0 for 64 KB
 * @param contains only entries with this text; empty for all
 * @param level only entries of this level or above (TRACE, DEBUG, INFO, WARN, ERROR); none for all
 */
public record LogQuery(
        String vertex, int subtask, int attempt, long offset, long maxBytes, String contains, @Nullable String level) {

    /** The whole log of a task, from its start. */
    public static LogQuery of(String vertex, int subtask, int attempt) {
        return new LogQuery(vertex, subtask, attempt, 0, 0, "", null);
    }

    public LogQuery withOffset(long from) {
        return new LogQuery(vertex, subtask, attempt, from, maxBytes, contains, level);
    }

    public LogQuery withMaxBytes(long bytes) {
        return new LogQuery(vertex, subtask, attempt, offset, bytes, contains, level);
    }

    public LogQuery withContains(String text) {
        return new LogQuery(vertex, subtask, attempt, offset, maxBytes, text, level);
    }

    public LogQuery withLevel(@Nullable String lowest) {
        return new LogQuery(vertex, subtask, attempt, offset, maxBytes, contains, lowest);
    }
}
