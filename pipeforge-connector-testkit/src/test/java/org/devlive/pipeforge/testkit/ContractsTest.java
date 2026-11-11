/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.TestData;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

class ContractsTest {

    @Test
    void readsSkippedGroupsAsAListOrAMap() {
        assertThat(Contracts.skippedGroups(List.of(Map.of("group", "types", "reason", "no types"))))
                .containsExactly(Map.entry("types", "no types"));
        assertThat(Contracts.skippedGroups(Map.of("batch", "in memory")))
                .containsExactly(Map.entry("batch", "in memory"));
        assertThat(Contracts.skippedGroups((Object) null)).isEmpty();
        assertThatThrownBy(() -> Contracts.skippedGroups(List.of("types")))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("need a group and a reason");
        assertThatThrownBy(() -> Contracts.skippedGroups("types")).hasMessageContaining("must be a list or a map");
    }

    @Test
    void findsTheDescriptorNextToTheComponent() {
        assertThat(Contracts.skippedGroups(FixtureRowsFactory.class)).containsOnlyKeys("batch");
        assertThat(Contracts.skippedGroups(TestkitConnectors.rows().getClass())).isEmpty();
        assertThat(Contracts.skippedGroups(String.class)).isEmpty();
    }

    @Test
    void writesJobsAsYamlWithACleanName() {
        TestJob job = TestJob.named("read DECIMAL(10, 2)")
                .source(TestkitConnectors.rows(), Map.of("dataset", "d"))
                .sink(TestkitConnectors.collect(), Map.of("dataset", "o"))
                .parallelism(3);

        assertThat(job.name()).isEqualTo("read-DECIMAL-10-2");
        assertThat(job.yaml())
                .contains("name: read-DECIMAL-10-2")
                .contains("parallelism: 3")
                .contains("timezone: Asia/Shanghai")
                .contains("type: testkit-rows");
        assertThat(TestJob.named("((").name()).isEqualTo("testkit");
        assertThat(TestJob.named("a".repeat(200)).name()).hasSize(128);
    }

    @Test
    void reportsFailedJobsWithTheirErrorAndAnalysis() {
        try (TestkitEngine engine = new TestkitEngine()) {
            TestTable table = TestData.rows("t", TestData.wideTable(), 5, 1);
            String in = Datasets.input(table);
            TestRun run = engine.run(TestJob.named("fails")
                    .source(TestkitConnectors.rows(), Map.of("dataset", in))
                    .sink(TestkitConnectors.collect(), Map.of("dataset", "no-such-output")));

            assertThatThrownBy(run::requireSuccess)
                    .isInstanceOf(AssertionFailedError.class)
                    .hasMessageContaining("Job fails ended with FAILED")
                    .hasMessageContaining("Hint:")
                    .hasMessageContaining("reconcile");
            assertThat(run.requireReconciled()).isSameAs(run);
            Datasets.release(in);
        }
    }

    @Test
    void forgetsReleasedDataSets() {
        String out = Datasets.output();
        assertThat(Datasets.written(out)).isEmpty();
        assertThat(Datasets.schemaChanges(out)).isEmpty();
        Datasets.release(out);
        assertThatThrownBy(() -> Datasets.written(out)).hasMessageContaining("No testkit output");
        assertThatThrownBy(() -> Datasets.schemaChanges(out)).hasMessageContaining("No testkit output");
    }
}
