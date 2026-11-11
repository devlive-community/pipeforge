/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.Events;

/** Broken connectors fail the contract with messages that say what is wrong. */
class ContractFailuresTest {

    private static Events run(Class<?> contract) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(contract))
                .execute()
                .testEvents();
    }

    private static List<String> failures(Events events) {
        return events.failed().stream()
                .map(event -> event.getTestDescriptor().getDisplayName() + ": "
                        + event.getPayload(TestExecutionResult.class)
                                .flatMap(TestExecutionResult::getThrowable)
                                .map(Throwable::getMessage)
                                .orElse(""))
                .toList();
    }

    private static List<String> names(Events events) {
        return events.stream()
                .map(Event::getTestDescriptor)
                .map(d -> d.getDisplayName())
                .distinct()
                .toList();
    }

    @Test
    void reportsRowsTheSourceDidNotRead() {
        Events events = run(BrokenConnectors.DroppingSource.class);
        List<String> failures = failures(events);

        assertThat(failures).anySatisfy(f -> assertThat(f)
                .startsWith("INT32: rows read by the source: expected ")
                .contains("missing (1):"));
        assertThat(failures).anySatisfy(f -> assertThat(f).startsWith("a single row: rows read by the source"));
        assertThat(failures).noneSatisfy(f -> assertThat(f).startsWith("an empty table"));
        assertThat(events.skipped().count()).isZero();
        assertThat(events.aborted().stream().map(e -> e.getTestDescriptor().getDisplayName()))
                .contains("BOOLEAN", "JSON")
                .doesNotContain("INT32", "STRING");
    }

    @Test
    void reportsOptionsThatDoNotPlan() {
        Events events = run(BrokenConnectors.MissingOptionSource.class);

        assertThat(failures(events))
                .anySatisfy(f ->
                        assertThat(f).startsWith("plans with valid options: ").contains("PF-CONF-0001"));
        assertThat(events.aborted().stream().map(e -> e.getTestDescriptor().getDisplayName()))
                .containsExactlyInAnyOrder("types", "batch");
    }

    @Test
    void reportsRowsTheSinkWroteTwiceAndUnknownSkippedGroups() {
        Events events = run(BrokenConnectors.DuplicatingSink.class);
        List<String> failures = failures(events);

        assertThat(failures).anySatisfy(f -> assertThat(f)
                .startsWith("a single row: rows written by the sink: expected 1 row(s) but got 2")
                .contains("unexpected (1):"));
        assertThat(failures)
                .anySatisfy(
                        f -> assertThat(f)
                                .startsWith(
                                        "skipped group no-such-group: 'no-such-group' in skipped-testkit-groups is not a testkit group"));
        assertThat(names(events)).contains("options");
    }

    @Test
    void runsEveryKindOfOptionCaseAndSkipsGroupsOfThePluginDescriptor() {
        Events events = run(FixtureContractTest.class);

        assertThat(failures(events)).isEmpty();
        assertThat(names(events))
                .contains(
                        "plans with valid options",
                        "rejects an unknown option",
                        "requires dataset",
                        "defaults ratio to 1.0",
                        "defaults limit to 10",
                        "defaults mode to ALL",
                        "rejects splits = 0 below its minimum",
                        "rejects splits = 10001 above its maximum",
                        "rejects ratio = 2.0 above its maximum",
                        "rejects limit = 0 below its minimum",
                        "rejects an unknown choice of mode",
                        "accepts the old name read-mode of mode",
                        "masks the sensitive option password",
                        "rejects table | query together",
                        "requires one of table | query",
                        "requires filter when mode is FILTERED");
        assertThat(events.aborted().stream()
                        .map(e -> e.getTestDescriptor().getDisplayName() + " "
                                + e.getPayload(TestExecutionResult.class)
                                        .flatMap(TestExecutionResult::getThrowable)
                                        .map(Throwable::getMessage)
                                        .orElse("")))
                .containsExactly(
                        "batch skipped: the fixture reads in memory; batch is covered by RowsSourceContractTest");
    }
}
