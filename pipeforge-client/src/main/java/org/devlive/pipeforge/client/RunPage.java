/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A page of runs, newest first.
 *
 * @param runs the runs
 * @param next the token of the next page, none on the last
 */
public record RunPage(List<Run> runs, @Nullable String next) {

    public RunPage {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }
}
