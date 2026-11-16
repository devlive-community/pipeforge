/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

/**
 * The memory limits of the tasks of a worker.
 *
 * @param maxRowBytes the largest row, estimated on the heap ({@code task.max-row-bytes}); a larger
 *     one fails with {@code PF-DATA-0007}
 * @param taskBytes the memory a task may hold in its write buffer before its reader waits ({@code
 *     worker.memory.task}); {@link Long#MAX_VALUE} for no limit
 */
public record MemoryLimits(long maxRowBytes, long taskBytes) {

    /** 64MB rows, no task limit: for local runs. */
    public static final MemoryLimits DEFAULTS = new MemoryLimits(64L * 1024 * 1024, Long.MAX_VALUE);

    public MemoryLimits {
        if (maxRowBytes <= 0 || taskBytes <= 0) {
            throw new IllegalArgumentException("memory limits must be positive");
        }
    }
}
