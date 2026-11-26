/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

/**
 * The answer to a failover.
 *
 * @param master the address of the master that gave its lease up
 */
public record Failover(String master) {}
