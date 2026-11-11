/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

/**
 * A place in the job text: 1-based line and column, and the path of the value, e.g. {@code
 * sink[0].inputs[1]}. Line 0 means the location is not known (the value was set in code).
 */
public record Location(int line, int column, String path) {

    public static Location of(String path) {
        return new Location(0, 0, path);
    }

    /** The location of a child value at the same place, for values without their own mark. */
    public Location child(String segment) {
        return new Location(line, column, join(path, segment));
    }

    static String join(String path, String segment) {
        if (path.isEmpty()) {
            return segment;
        }
        return segment.startsWith("[") ? path + segment : path + "." + segment;
    }

    @Override
    public String toString() {
        String where = path.isEmpty() ? "the document" : path;
        return line > 0 ? "line " + line + ", column " + column + " (" + where + ")" : where;
    }
}
