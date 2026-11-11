/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Sink capability: Withdraws the data a failed run has written. */
@Evolving
public interface SupportsRollback {
    /** Rollback modes. */
    enum Mode {
        STAGING_SWAP,
        STAGING_APPEND,
        DELETE_BY_RUN,
        PARTITION_OVERWRITE,
        COMMIT_ON_FINISH
    }

    java.util.Set<Mode> supportedRollbackModes();

    void prepare(Mode mode, String runId) throws Exception;

    void finalizeSuccess(Mode mode, String runId) throws Exception;

    void rollback(Mode mode, String runId) throws Exception;

    /** Removes leftovers of runs that ended without finalizing or rolling back. */
    default void cleanupOrphans(Mode mode) throws Exception {}
}
