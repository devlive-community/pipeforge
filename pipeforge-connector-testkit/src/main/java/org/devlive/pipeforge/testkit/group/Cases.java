/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.group;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.TestAbortedException;

/** Helpers to create the dynamic tests of a group. */
final class Cases {

    private Cases() {}

    static DynamicTest test(String name, Executable body) {
        return DynamicTest.dynamicTest(name, body);
    }

    /** A case that is reported as skipped with {@code reason}. */
    static DynamicTest skipped(String name, String reason) {
        return DynamicTest.dynamicTest(name, () -> {
            throw new TestAbortedException(reason);
        });
    }
}
