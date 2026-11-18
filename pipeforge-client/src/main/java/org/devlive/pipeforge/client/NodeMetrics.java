/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

/**
 * The counters of one node of the plan.
 *
 * @param node the node ID
 * @param rowsIn rows in
 * @param rowsOut rows out
 * @param rowsFiltered rows dropped
 * @param rowsExpanded rows added
 * @param rowsDirty bad rows skipped
 * @param bytesIn bytes in
 * @param bytesOut bytes out
 */
public record NodeMetrics(
        String node,
        long rowsIn,
        long rowsOut,
        long rowsFiltered,
        long rowsExpanded,
        long rowsDirty,
        long bytesIn,
        long bytesOut) {}
