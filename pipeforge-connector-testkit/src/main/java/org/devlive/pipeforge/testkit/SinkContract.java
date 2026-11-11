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
import org.devlive.pipeforge.testkit.group.SinkGroups;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The contract tests of a sink. Extend it, return the sink in {@link #sink()}, and JUnit runs the
 * {@code options}, {@code types} and {@code batch} groups. Groups that do not apply are declared in
 * {@code skipped-testkit-groups} of the plugin's {@code plugin.yaml}.
 */
@ExtendWith(TestkitExtension.class)
public abstract class SinkContract {

    protected abstract SinkUnderTest sink();

    /** The groups to run; later commits of the testkit add groups here. */
    protected List<ContractGroup> groups(SinkUnderTest sink) {
        return SinkGroups.all(sink);
    }

    /** Groups that do not apply, with the reason; by default those of the plugin's {@code plugin.yaml}. */
    protected Map<String, String> skippedGroups(SinkUnderTest sink) {
        return Contracts.skippedGroups(sink.factory().getClass());
    }

    @TestFactory
    Stream<DynamicNode> contract(TestkitEngine engine) {
        SinkUnderTest sink = sink();
        return Contracts.run(groups(sink), skippedGroups(sink), engine);
    }
}
