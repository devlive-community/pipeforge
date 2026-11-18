/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A worker of the cluster.
 *
 * @param id its ID
 * @param rpcAddress its RPC address
 * @param dataAddress its data address
 * @param resourceGroup its resource group
 * @param labels its labels
 * @param cpuCores CPU cores
 * @param taskHeapBytes task heap
 * @param managedBytes managed memory
 * @param networkBytes network buffers
 * @param tasksRunning tasks running now
 * @param sinceHeartbeatMs since its last heartbeat
 * @param version its version
 * @param slots its slots
 */
public record Worker(
        String id,
        String rpcAddress,
        String dataAddress,
        @Nullable String resourceGroup,
        Map<String, String> labels,
        int cpuCores,
        long taskHeapBytes,
        long managedBytes,
        long networkBytes,
        int tasksRunning,
        long sinceHeartbeatMs,
        @Nullable String version,
        List<Slot> slots) {

    public Worker {
        labels = labels == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(labels));
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
}
