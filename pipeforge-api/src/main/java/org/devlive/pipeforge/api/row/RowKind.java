/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.row;

import org.devlive.pipeforge.api.annotation.Stable;

/** The change a row describes. Bounded sources only produce {@link #INSERT}. */
@Stable
public enum RowKind {
    INSERT("+I"),
    UPDATE_BEFORE("-U"),
    UPDATE_AFTER("+U"),
    DELETE("-D");

    private final String shortName;

    RowKind(String shortName) {
        this.shortName = shortName;
    }

    /** Compact form used in logs and console output: {@code +I}, {@code -U}, {@code +U}, {@code -D}. */
    public String shortName() {
        return shortName;
    }

    /** Whether the row adds data ({@code INSERT} or {@code UPDATE_AFTER}). */
    public boolean isAccumulate() {
        return this == INSERT || this == UPDATE_AFTER;
    }
}
