/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

/**
 * A rate in rows per second.
 *
 * @param current over the last 5 seconds
 * @param average since the job started
 * @param peak the highest rate over one second
 */
public record Rate(double current, double average, double peak) {

    public static final Rate ZERO = new Rate(0, 0, 0);
}
