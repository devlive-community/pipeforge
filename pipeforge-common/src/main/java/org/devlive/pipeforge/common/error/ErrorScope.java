/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where an error happened: job, run, node, subtask, table and split. Every part is optional;
 * {@link #toMap()} contains only the parts that are set.
 */
public record ErrorScope(
        @Nullable String job,
        @Nullable String run,
        @Nullable String node,
        @Nullable Integer subtask,
        @Nullable String table,
        @Nullable String split) {

    /** A scope without any location. */
    public static ErrorScope none() {
        return new ErrorScope(null, null, null, null, null, null);
    }

    /** A scope that names a job node. */
    public static ErrorScope node(String node) {
        return none().withNode(node);
    }

    public ErrorScope withJob(String value) {
        return new ErrorScope(value, run, node, subtask, table, split);
    }

    public ErrorScope withRun(String value) {
        return new ErrorScope(job, value, node, subtask, table, split);
    }

    public ErrorScope withNode(String value) {
        return new ErrorScope(job, run, value, subtask, table, split);
    }

    public ErrorScope withSubtask(int value) {
        return new ErrorScope(job, run, node, value, table, split);
    }

    public ErrorScope withTable(String value) {
        return new ErrorScope(job, run, node, subtask, value, split);
    }

    public ErrorScope withSplit(String value) {
        return new ErrorScope(job, run, node, subtask, table, value);
    }

    /** The parts that are set, in order job, run, node, subtask, table, split. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "job", job);
        put(map, "run", run);
        put(map, "node", node);
        put(map, "subtask", subtask);
        put(map, "table", table);
        put(map, "split", split);
        return map;
    }

    private static void put(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
