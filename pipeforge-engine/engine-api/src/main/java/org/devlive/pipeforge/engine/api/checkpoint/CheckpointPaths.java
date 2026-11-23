/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api.checkpoint;

/**
 * The layout of a job's checkpoints in the storage (checkpoint design, section 5):
 *
 * <pre>
 * &lt;jobId&gt;/chk-&lt;n&gt;/_metadata, enum-&lt;node&gt;.state, &lt;node&gt;-&lt;subtask&gt;.state
 * &lt;jobId&gt;/savepoints/&lt;name&gt;-&lt;timestamp&gt;/...
 * </pre>
 */
public final class CheckpointPaths {

    private CheckpointPaths() {}

    /** The directory of a job's checkpoints. */
    public static String job(String jobId) {
        return check(jobId) + "/";
    }

    /** The directory of checkpoint {@code n} of a job. */
    public static String checkpoint(String jobId, long n) {
        return job(jobId) + "chk-" + n + "/";
    }

    /** The directory of a savepoint, which automatic cleanup never deletes. */
    public static String savepoint(String jobId, String name, long timestampMs) {
        return job(jobId) + "savepoints/" + check(name) + "-" + timestampMs + "/";
    }

    /** A file in a directory of one of the methods above. */
    public static String file(String directory, String name) {
        return directory + check(name);
    }

    /**
     * The checkpoint or savepoint directory a file is in, or {@code null} for a file outside one.
     */
    public static @org.jspecify.annotations.Nullable String directoryOf(String path) {
        String[] parts = path.split("/", -1);
        if (parts.length >= 3 && parts[1].startsWith("chk-")) {
            return parts[0] + "/" + parts[1] + "/";
        }
        if (parts.length >= 4 && parts[1].equals("savepoints")) {
            return parts[0] + "/" + parts[1] + "/" + parts[2] + "/";
        }
        return null;
    }

    /** Names stay one path segment of plain characters, so no path can leave the job's directory. */
    static String check(String name) {
        if (name.isEmpty() || !name.matches("[A-Za-z0-9_.\\-]+") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException(
                    "'" + name + "' is not a name for checkpoint storage: use letters, digits, '_', '-' and '.'");
        }
        return name;
    }
}
