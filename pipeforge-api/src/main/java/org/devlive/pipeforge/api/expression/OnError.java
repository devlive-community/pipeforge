/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.expression;

import java.util.Locale;
import org.devlive.pipeforge.api.annotation.Evolving;

/**
 * What happens when an expression fails while evaluating a row, for example a division by zero or
 * a failed cast. Components that evaluate expressions expose it as their {@code on-error} option.
 */
@Evolving
public enum OnError {
    /** The row is a bad row, handled by the dirty data policy of the job ({@code PF-DATA-0006}). */
    DIRTY,
    /** The expression yields NULL and the row continues; the failure is counted. */
    NULL,
    /** The job fails ({@code PF-DATA-0006}). */
    FAIL;

    /** The option value: {@code dirty}, {@code null} or {@code fail}. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@code dirty}, {@code null} or {@code fail}, ignoring case. */
    public static OnError parse(String text) {
        for (OnError value : values()) {
            if (value.name().equalsIgnoreCase(text.trim())) {
                return value;
            }
        }
        throw new IllegalArgumentException("on-error must be dirty, null or fail, not '" + text + "'");
    }
}
