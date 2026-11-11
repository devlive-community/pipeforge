/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.explain;

import java.util.Locale;
import java.util.Optional;

/** Output formats of a plan explanation. */
public enum ExplainFormat {
    /** Aligned text for terminals. */
    TEXT,
    /** The complete structured plan. */
    JSON;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ExplainFormat> parse(String text) {
        for (ExplainFormat format : values()) {
            if (format.id().equalsIgnoreCase(text.trim())) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
