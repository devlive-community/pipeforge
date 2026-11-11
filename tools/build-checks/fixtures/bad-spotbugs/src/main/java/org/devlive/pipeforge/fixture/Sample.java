/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.fixture;

/** Compares strings by reference (SpotBugs: ES_COMPARING_PARAMETER_STRING_WITH_EQ). */
public final class Sample {
    private Sample() {}

    @SuppressWarnings("ReferenceEquality")
    public static boolean same(String left, String right) {
        return left == right;
    }
}
