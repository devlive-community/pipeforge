/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Captures what every console sink of the JVM prints, for the duration of a test. */
final class CapturedOutput implements AutoCloseable {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    CapturedOutput() {
        ConsoleOutput.redirect(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    }

    synchronized String text() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    List<String> lines() {
        String text = text();
        return text.isEmpty()
                ? List.of()
                : Arrays.asList(text.split("\n", -1)).subList(0, text.split("\n", -1).length - 1);
    }

    synchronized void clear() {
        bytes.reset();
    }

    @Override
    public void close() {
        ConsoleOutput.redirect(null);
    }
}
