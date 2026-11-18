/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

/**
 * A split of a run and its reader.
 *
 * @param node the source node
 * @param split the split ID
 * @param subtask the reader
 * @param state ASSIGNED while it is read, FINISHED once read
 */
public record RunSplit(String node, String split, int subtask, String state) {}
