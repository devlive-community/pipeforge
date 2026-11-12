/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import java.io.PrintStream;
import org.jspecify.annotations.Nullable;

/** The streams the sink prints to; tests redirect them to read the output back. */
final class ConsoleOutput {

    private static volatile @Nullable PrintStream redirect;

    private ConsoleOutput() {}

    static PrintStream stream(ConsoleOptions.Target target) {
        PrintStream override = redirect;
        if (override != null) {
            return override;
        }
        return target == ConsoleOptions.Target.STDERR ? System.err : System.out;
    }

    /** Sends the output of every console sink in this JVM to {@code stream}, or back to the real streams with null. */
    static void redirect(@Nullable PrintStream stream) {
        redirect = stream;
    }
}
