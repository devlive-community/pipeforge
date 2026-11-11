/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricCatalogTest {

    private static List<String> components(Class<? extends Record> type, Set<String> except) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> !except.contains(name))
                .toList();
    }

    @Test
    void listsEveryMetricTheEngineReports() {
        MetricCatalog catalog = MetricCatalog.global();

        for (String name : components(JobMetrics.class, Set.of("nodes"))) {
            assertThat(catalog.find("job", name)).as("job metric %s", name).isPresent();
        }
        for (String name : components(NodeMetrics.class, Set.of("node", "kind"))) {
            assertThat(catalog.find("node", name)).as("node metric %s", name).isPresent();
        }
        assertThat(catalog.metrics()).allSatisfy(m -> {
            boolean reported = m.scope().equals("job")
                    ? components(JobMetrics.class, Set.of()).contains(m.name())
                    : components(NodeMetrics.class, Set.of()).contains(m.name());
            assertThat(reported).as("%s.%s is reported", m.scope(), m.name()).isTrue();
            assertThat(m.description()).containsKeys("en-US", "zh-CN");
        });
        assertThat(catalog.find("job", "readRowsPerSecond").orElseThrow().unit())
                .isEqualTo("rows/s");
    }

    @Test
    void rejectsMalformedCatalogs() {
        assertThatThrownBy(() -> MetricCatalog.parse("a: b")).hasMessageContaining("must be a list");
        assertThatThrownBy(
                        () -> MetricCatalog.parse(
                                """
                        - name: RowsRead
                          scope: task
                          type: histogram
                          unit: bytes
                          description: { en-US: "x" }
                        - name: ok
                          scope: job
                          type: counter
                          unit: rows
                          description: { en-US: "x", zh-CN: "x" }
                        - name: ok
                          scope: job
                          type: counter
                          unit: rows
                          description: { en-US: "x", zh-CN: " " }
                        - just text
                        """))
                .hasMessageContaining("task.RowsRead: the name must be lower camel case")
                .hasMessageContaining("unknown scope 'task'")
                .hasMessageContaining("unknown type 'histogram'")
                .hasMessageContaining("unknown unit 'bytes'")
                .hasMessageContaining("no zh-CN description")
                .hasMessageContaining("job.ok: listed twice")
                .hasMessageContaining("entry is not a mapping");
    }

    @Test
    void reconcilesAndFindsNodes() {
        NodeMetrics map = new NodeMetrics("map", "transform", 10, 8, 3, 1, 0.25);
        JobMetrics metrics = new JobMetrics(1500, 10, 7, 3, 1, 0, 1, Rate.ZERO, Rate.ZERO, 0, 0, 1, 1, List.of(map));

        assertThat(metrics.reconciled()).isTrue();
        assertThat(metrics.node("map")).contains(map);
        assertThat(metrics.node("nope")).isEmpty();
        assertThat(map.selectivity()).isEqualTo(0.8);
        assertThat(new NodeMetrics("x", "source", 0, 0, 0, 0, -1).selectivity()).isEqualTo(-1);
        assertThat(new JobMetrics(0, 10, 9, 0, 0, 0, 0, Rate.ZERO, Rate.ZERO, 0, 0, 1, 0, List.of()).reconciled())
                .isFalse();
    }
}
