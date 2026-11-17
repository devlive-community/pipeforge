/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.JobDefinition;
import org.devlive.pipeforge.core.job.Location;
import org.devlive.pipeforge.core.job.NodeDefinition;
import org.devlive.pipeforge.core.job.NodeKind;
import org.junit.jupiter.api.Test;

class PreviewServiceTest {

    static NodeDefinition node(String id, NodeKind kind, String... inputs) {
        return new NodeDefinition(id, kind, "t", null, List.of(inputs), false, 4, Map.of(), Location.of(id));
    }

    static final JobDefinition JOB = new JobDefinition(
            1,
            "job",
            null,
            ZoneOffset.UTC,
            Map.of(),
            ReadonlyConfig.of(Map.of()),
            List.of(
                    node("src", NodeKind.SOURCE),
                    node("map", NodeKind.TRANSFORM, "src"),
                    node("filter", NodeKind.TRANSFORM, "map"),
                    node("out", NodeKind.SINK, "filter")));

    @Test
    void cutsTheJobAfterTheNodeAndReplacesTheSinks() {
        assertThat(PreviewService.target(JOB, null))
                .as("a dry run shows the sink's input")
                .isEqualTo("filter");
        assertThat(PreviewService.target(JOB, "out")).isEqualTo("filter");
        assertThat(PreviewService.target(JOB, "map")).isEqualTo("map");
        assertThatThrownBy(() -> PreviewService.target(JOB, "nothing")).isInstanceOf(PipeForgeException.class);

        JobDefinition cut = PreviewService.rewrite(JOB, "map", "buffer-1");
        assertThat(cut.nodes()).extracting(NodeDefinition::id).containsExactly("src", "map", "pipeforge-preview");
        assertThat(cut.nodes()).extracting(NodeDefinition::parallelism).containsOnly(1);
        NodeDefinition sink = cut.nodes().get(2);
        assertThat(sink.type()).isEqualTo(PreviewSink.TYPE);
        assertThat(sink.inputs()).containsExactly("map");
        assertThat(sink.options()).containsEntry("buffer", "buffer-1");
    }

    @Test
    void showsValuesWithoutLoss() {
        assertThat(PreviewService.json(5L)).isEqualTo(5L);
        assertThat(PreviewService.json(Long.MAX_VALUE)).isEqualTo("9223372036854775807");
        assertThat(PreviewService.json(new BigDecimal("1.50"))).isEqualTo("1.50");
        assertThat(PreviewService.json(Double.NaN)).isEqualTo("NaN");
        assertThat(PreviewService.json(new byte[] {1, 2})).isEqualTo("base64:AQI=");
        assertThat(PreviewService.json(Instant.EPOCH)).isEqualTo("1970-01-01T00:00:00Z");
        assertThat(PreviewService.json(Arrays.asList(1, null))).isEqualTo(Arrays.asList(1, null));
        assertThat(PreviewService.json(null)).isNull();
    }
}
