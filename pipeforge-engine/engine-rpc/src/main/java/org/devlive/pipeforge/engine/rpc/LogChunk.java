/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.Objects;

/**
 * A part of the log of a task.
 *
 * @param offset where the next read continues: the end of the part of the log this chunk covers;
 *     with filters, {@code text} holds only the matching entries of that part
 * @param text whole lines, UTF-8
 * @param eof the chunk reaches the end of the log and the task has ended: nothing more follows
 */
public record LogChunk(long offset, String text, boolean eof) {

    public LogChunk {
        Objects.requireNonNull(text, "text");
    }
}
