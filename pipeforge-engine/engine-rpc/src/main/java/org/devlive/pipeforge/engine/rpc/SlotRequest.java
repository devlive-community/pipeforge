/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.Objects;

/**
 * A job master reserves one slot of a worker for a job.
 *
 * @param allocationId names the reservation; deployments into the slot and its release carry it
 * @param slot the index of the slot on the worker
 */
public record SlotRequest(String jobId, String runId, String allocationId, int slot) {
    public SlotRequest {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(allocationId, "allocationId");
        if (slot < 0) {
            throw new IllegalArgumentException("A slot index is not negative: " + slot);
        }
    }
}
