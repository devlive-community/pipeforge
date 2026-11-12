/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.util.Arrays;

/** The entry point of {@code bin/pipeforge}. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        configureLogging(args);
        System.exit(new Cli(Environment.system()).run(args));
    }

    /**
     * Engine and plugin logs go to the standard error: warnings only, everything from INFO with
     * --verbose. Must run before the first logger is created.
     */
    static void configureLogging(String[] args) {
        boolean verbose = Arrays.stream(args).anyMatch(a -> a.equals("-v") || a.equals("--verbose"));
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", verbose ? "info" : "warn");
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.showLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
    }
}
