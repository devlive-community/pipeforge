/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.dirty;

import java.util.Locale;

/**
 * What a job does with bad rows ({@code env.dirty}): rows that a source cannot read, a transform
 * cannot transform or a sink cannot write because of their data.
 *
 * @param mode {@code fail} the job at the first bad row, or {@code skip} it and count it
 * @param maxRows with {@code skip}: the job fails once it skipped more rows than this; -1 for no
 *     limit
 */
public record DirtyPolicy(Mode mode, long maxRows) {

    /** Fail at the first bad row. */
    public static final DirtyPolicy FAIL = new DirtyPolicy(Mode.FAIL, -1);

    public enum Mode {
        FAIL,
        SKIP;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public DirtyPolicy {
        java.util.Objects.requireNonNull(mode, "mode");
        if (maxRows < -1) {
            throw new IllegalArgumentException("max-rows must be 0 or more, or -1 for no limit");
        }
    }

    public boolean skips() {
        return mode == Mode.SKIP;
    }

    /** Whether {@code rows} skipped rows are more than the job allows. */
    public boolean exceeded(long rows) {
        return skips() && maxRows >= 0 && rows > maxRows;
    }
}
