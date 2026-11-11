/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.devlive.pipeforge.testkit.group.TransformGroups;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The contract tests of a transform. Extend it, return the transform in {@link #transform()}, and
 * JUnit runs the {@code options} and {@code transform} groups. Groups that do not apply are
 * declared in {@code skipped-testkit-groups} of the plugin's {@code plugin.yaml}.
 */
@ExtendWith(TestkitExtension.class)
public abstract class TransformContract {

    protected abstract TransformUnderTest transform();

    /** The groups to run; later commits of the testkit add groups here. */
    protected List<ContractGroup> groups(TransformUnderTest transform) {
        return TransformGroups.all(transform);
    }

    /** Groups that do not apply, with the reason; by default those of the plugin's {@code plugin.yaml}. */
    protected Map<String, String> skippedGroups(TransformUnderTest transform) {
        return Contracts.skippedGroups(transform.factory().getClass());
    }

    @TestFactory
    Stream<DynamicNode> contract(TestkitEngine engine) {
        TransformUnderTest transform = transform();
        return Contracts.run(groups(transform), skippedGroups(transform), engine);
    }
}
