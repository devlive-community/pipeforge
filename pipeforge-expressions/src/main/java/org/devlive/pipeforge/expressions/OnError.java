/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import java.util.Locale;

/** What happens when an expression fails at run time (division by zero, failed cast, overflow). */
public enum OnError {
    /** The row is a bad row, handled by the dirty data policy (PF-DATA-0006, kind DATA). */
    DIRTY,
    /** The expression yields NULL and the row continues. */
    NULL,
    /** The job fails (PF-DATA-0006, kind FATAL). */
    FAIL;

    /** Parses {@code dirty}, {@code null} or {@code fail}. */
    public static OnError parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
