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
import org.devlive.pipeforge.testkit.group.SourceGroups;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The contract tests of a source. Extend it, return the source in {@link #source()}, and JUnit runs
 * the {@code options}, {@code types} and {@code batch} groups, each as a container of cases:
 *
 * <pre>{@code
 * class FakeSourceContractTest extends SourceContract {
 *     @Override
 *     protected SourceUnderTest source() {
 *         return new FakeSourceUnderTest();
 *     }
 * }
 * }</pre>
 *
 * <p>Groups that do not apply are declared with a reason in {@code skipped-testkit-groups} of the
 * plugin's {@code plugin.yaml}; they are reported as skipped.
 */
@ExtendWith(TestkitExtension.class)
public abstract class SourceContract {

    protected abstract SourceUnderTest source();

    /** The groups to run; later commits of the testkit add groups here. */
    protected List<ContractGroup> groups(SourceUnderTest source) {
        return SourceGroups.all(source);
    }

    /** Groups that do not apply, with the reason; by default those of the plugin's {@code plugin.yaml}. */
    protected Map<String, String> skippedGroups(SourceUnderTest source) {
        return Contracts.skippedGroups(source.factory().getClass());
    }

    @TestFactory
    Stream<DynamicNode> contract(TestkitEngine engine) {
        SourceUnderTest source = source();
        return Contracts.run(groups(source), skippedGroups(source), engine);
    }
}
