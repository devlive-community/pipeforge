/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DynamicNode;

/**
 * A group of contract test cases, e.g. {@code options}. The names are those of the plugin catalog
 * and of {@code skipped-testkit-groups} in {@code plugin.yaml}.
 *
 * @param name the group name
 * @param cases creates the cases of the group; called only when the group runs
 */
public record ContractGroup(String name, Function<TestkitEngine, List<DynamicNode>> cases) {

    public List<DynamicNode> cases(TestkitEngine engine) {
        return cases.apply(engine);
    }
}
