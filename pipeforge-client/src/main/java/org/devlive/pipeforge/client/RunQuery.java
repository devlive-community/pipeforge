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
 * Which runs to list: by states and job name, a page at a time.
 *
 * @param statuses the states to list; empty for all
 * @param job only the runs of this job
 * @param limit runs per page; 0 for the server's default
 * @param page the token of the page, from {@link RunPage#next()}; none for the first
 */
public record RunQuery(List<String> statuses, @Nullable String job, int limit, @Nullable String page) {

    public RunQuery {
        statuses = List.copyOf(statuses);
    }

    /** Every run, the first page. */
    public static RunQuery all() {
        return new RunQuery(List.of(), null, 0, null);
    }

    public RunQuery withStatuses(String... states) {
        return new RunQuery(List.of(states), job, limit, page);
    }

    public RunQuery withJob(@Nullable String name) {
        return new RunQuery(statuses, name, limit, page);
    }

    public RunQuery withLimit(int runs) {
        return new RunQuery(statuses, job, runs, page);
    }

    public RunQuery withPage(@Nullable String token) {
        return new RunQuery(statuses, job, limit, token);
    }
}
