/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.fixture;

/** Creates an exception without throwing it (Error Prone: DeadException). */
public final class Sample {
    private Sample() {}

    public static int check(int value) {
        if (value < 0) {
            new IllegalArgumentException("negative");
        }
        return value;
    }
}
