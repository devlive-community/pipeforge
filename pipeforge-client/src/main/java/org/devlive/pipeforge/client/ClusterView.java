/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.util.List;

/**
 * The cluster as the master sees it.
 *
 * @param master the master
 * @param slots slots in all
 * @param slotsUsed slots runs hold
 * @param usage slotsUsed / slots, from 0 to 1
 * @param workers the workers
 */
public record ClusterView(Master master, int slots, int slotsUsed, double usage, List<Worker> workers) {

    public ClusterView {
        workers = workers == null ? List.of() : List.copyOf(workers);
    }
}
