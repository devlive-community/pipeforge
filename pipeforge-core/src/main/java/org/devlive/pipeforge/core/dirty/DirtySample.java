/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.dirty;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A bad row that was skipped, as a sample for users: where it was refused, why, and what it held.
 *
 * @param node the node that refused it
 * @param table the table of the row, or {@code null} when a reader could not read it as a row
 * @param code the error code, e.g. {@code PF-DATA-0006}
 * @param message the error message
 * @param row the values of the row as text, each cut to {@link #MAX_VALUE_CHARS} characters; empty
 *     when there is no row
 * @param atMs when it was refused, in milliseconds since the epoch
 */
public record DirtySample(String node, @Nullable String table, String code, String message, String row, long atMs) {

    /** How many samples a run keeps. */
    public static final int MAX_SAMPLES = 20;

    /** How long one value of a sample may be. */
    public static final int MAX_VALUE_CHARS = 256;

    public DirtySample {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(row, "row");
    }
}
