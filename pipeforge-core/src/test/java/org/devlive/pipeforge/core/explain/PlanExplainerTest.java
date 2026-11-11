/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.core.TestComponents;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Compares explain output with the golden files in {@code src/test/resources/explain}. Run with
 * {@code -Dgolden.update=true} to rewrite them after an intended change, and review the diff.
 */
class PlanExplainerTest {

    private static final Path GOLDEN = Path.of("src", "test", "resources", "explain");

    private static JobPlan plan(String name) throws IOException {
        String yaml = Files.readString(GOLDEN.resolve(name + ".yaml"), StandardCharsets.UTF_8);
        ReadonlyConfig cluster = ReadonlyConfig.of(Map.of("sink.limit", 50));
        return TestComponents.plan(yaml, cluster);
    }

    @ParameterizedTest
    @ValueSource(strings = {"linear", "merge"})
    void textAndJsonMatchTheGoldenFiles(String name) throws IOException {
        JobPlan plan = plan(name);
        compare(name + ".txt", PlanExplainer.explain(plan, ExplainFormat.TEXT));
        compare(name + ".json", PlanExplainer.explain(plan, ExplainFormat.JSON));
    }

    @Test
    void jsonHoldsTheWholePlanAndMasksSecrets() throws IOException {
        JsonNode json = new ObjectMapper().readTree(PlanExplainer.json(plan("linear")));
        assertThat(json.path("job").path("mode").asText()).isEqualTo("batch");
        assertThat(json.path("variables").path("biz_date").asText()).isEqualTo("2026-11-06");
        JsonNode src = json.path("nodes").get(0);
        assertThat(src.path("outputTables").get(0).path("primaryKey").get(0).asText())
                .isEqualTo("id");
        assertThat(json.toString()).doesNotContain("s3cret").contains("******");
        assertThat(PlanExplainer.text(plan("linear"))).doesNotContain("s3cret");
    }

    @Test
    void formatsParseCaseInsensitively() {
        assertThat(ExplainFormat.parse(" JSON ")).contains(ExplainFormat.JSON);
        assertThat(ExplainFormat.parse("text")).contains(ExplainFormat.TEXT);
        assertThat(ExplainFormat.parse("html")).isEmpty();
        assertThat(ExplainFormat.JSON.id()).isEqualTo("json");
    }

    private static void compare(String file, String actual) throws IOException {
        Path path = GOLDEN.resolve(file);
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(path, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).as(file).isEqualTo(Files.readString(path, StandardCharsets.UTF_8));
    }
}
