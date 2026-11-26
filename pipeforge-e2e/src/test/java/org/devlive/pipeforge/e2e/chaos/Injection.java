/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

/**
 * One fault of a plan.
 *
 * @param number its place in the plan, from 1
 * @param atMs when it starts, after the start of the run
 * @param durationMs how long it lasts before it heals: the process is started again, the link and
 *     the storage work again
 * @param param the delay in milliseconds, the share of lost packets in percent; 0 for the others
 */
public record Injection(int number, long atMs, Fault fault, String target, long durationMs, long param) {

    /** How it reads in the record and in messages. */
    public String describe() {
        return "#" + number + " " + fault + " " + target + (param == 0 ? "" : " " + param) + " for " + durationMs
                + " ms at " + atMs + " ms";
    }
}
