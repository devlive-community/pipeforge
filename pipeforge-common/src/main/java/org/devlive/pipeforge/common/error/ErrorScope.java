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
 * Where an error happened: job, run, node, subtask, table and split, and for errors in a job
 * definition the line, column and path of the offending value. Every part is optional; {@link
 * #toMap()} contains only the parts that are set.
 *
 * @param line 1-based line in the job definition
 * @param column 1-based column in the job definition
 * @param path path of the value in the job definition, e.g. {@code sink[0].inputs}
 */
public record ErrorScope(
        @Nullable String job,
        @Nullable String run,
        @Nullable String node,
        @Nullable Integer subtask,
        @Nullable String table,
        @Nullable String split,
        @Nullable Integer line,
        @Nullable Integer column,
        @Nullable String path) {

    /** A scope without any location. */
    public static ErrorScope none() {
        return new ErrorScope(null, null, null, null, null, null, null, null, null);
    }

    /** A scope that names a job node. */
    public static ErrorScope node(String node) {
        return none().withNode(node);
    }

    /** A scope that points at a value in a job definition; a line below 1 means the line is unknown. */
    public static ErrorScope definition(int line, int column, String path) {
        return none().withDefinition(line, column, path);
    }

    public ErrorScope withJob(String value) {
        return new ErrorScope(value, run, node, subtask, table, split, line, column, path);
    }

    public ErrorScope withRun(String value) {
        return new ErrorScope(job, value, node, subtask, table, split, line, column, path);
    }

    public ErrorScope withNode(String value) {
        return new ErrorScope(job, run, value, subtask, table, split, line, column, path);
    }

    public ErrorScope withSubtask(int value) {
        return new ErrorScope(job, run, node, value, table, split, line, column, path);
    }

    public ErrorScope withTable(String value) {
        return new ErrorScope(job, run, node, subtask, value, split, line, column, path);
    }

    public ErrorScope withSplit(String value) {
        return new ErrorScope(job, run, node, subtask, table, value, line, column, path);
    }

    /** The location of a value in the job definition; a line below 1 keeps only the path. */
    public ErrorScope withDefinition(int lineValue, int columnValue, String pathValue) {
        boolean known = lineValue > 0;
        return new ErrorScope(
                job,
                run,
                node,
                subtask,
                table,
                split,
                known ? lineValue : null,
                known ? columnValue : null,
                pathValue.isEmpty() ? null : pathValue);
    }

    /**
     * The scope described by {@code map}, as {@link #toMap()} writes it; numbers may be given as
     * text, and unknown keys are ignored.
     *
     * @throws IllegalArgumentException if subtask, line or column is not a number
     */
    public static ErrorScope fromMap(Map<String, ?> map) {
        return new ErrorScope(
                text(map, "job"),
                text(map, "run"),
                text(map, "node"),
                number(map, "subtask"),
                text(map, "table"),
                text(map, "split"),
                number(map, "line"),
                number(map, "column"),
                text(map, "path"));
    }

    private static @Nullable String text(Map<String, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static @Nullable Integer number(Map<String, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Scope " + key + " is not a number: " + value, e);
        }
    }

    /** The parts that are set, in order job, run, node, subtask, table, split, line, column, path. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "job", job);
        put(map, "run", run);
        put(map, "node", node);
        put(map, "subtask", subtask);
        put(map, "table", table);
        put(map, "split", split);
        put(map, "line", line);
        put(map, "column", column);
        put(map, "path", path);
        return map;
    }

    private static void put(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
