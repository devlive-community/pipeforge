/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginDocsTest {

    @Test
    void exportsOptionsRulesAndCapabilities(@TempDir Path dir) throws IOException {
        PluginDescriptor descriptor = PluginInspectorTest.descriptor("  - { type: source, identifier: good, class: "
                + PluginInspectorTest.FIXTURES + "GoodSourceFactory }\n");
        PluginInspector.Result result =
                PluginInspector.inspect(descriptor, getClass().getClassLoader());
        Path file = PluginDocs.write(PluginDocs.model(descriptor, result.components()), dir);
        assertThat(file.getFileName()).hasToString("sample.json");

        JsonNode json = new ObjectMapper().readTree(file.toFile());
        assertThat(json.get("format").asInt()).isEqualTo(PluginDocs.FORMAT_VERSION);
        assertThat(json.get("datasource").get("versions").get(0).asText()).isEqualTo("1.0");
        JsonNode component = json.get("components").get(0);
        assertThat(component.get("type").asText()).isEqualTo("source");
        assertThat(component.get("capabilities").toString())
                .isEqualTo("[\"SupportsLimitPushDown\",\"SupportsRowCount\"]");
        assertThat(component.get("exclusive").toString()).isEqualTo("[[\"table\",\"query\"]]");
        assertThat(component.get("conditions").get(0).toString())
                .isEqualTo("{\"option\":\"mode\",\"value\":\"SAFE\",\"required\":[\"key\"]}");
        assertThat(component.get("nativeNamespaces").get(0).asText()).isEqualTo("good.client");

        JsonNode options = component.get("options");
        assertThat(options.findValuesAsText("key"))
                .containsExactly("rows", "table", "query", "interval", "password", "mode", "key");
        assertThat(options.findValuesAsText("requirement"))
                .containsExactly("required", "one-of", "one-of", "optional", "optional", "optional", "conditional");
        JsonNode rows = options.get(0);
        assertThat(rows.get("default").asInt()).isEqualTo(10);
        assertThat(rows.get("min").asDouble()).isEqualTo(1.0);
        assertThat(rows.get("group").asText()).isEqualTo("data");
        assertThat(rows.get("since").asText()).isEqualTo("0.1.0");
        assertThat(options.get(3).get("default").asText()).isEqualTo("30s");
        assertThat(options.get(4).get("default").isNull()).isTrue();
        assertThat(options.get(4).get("sensitive").asBoolean()).isTrue();
        assertThat(options.get(5).get("values").toString()).isEqualTo("[\"FAST\",\"SAFE\"]");
    }

    @Test
    void writesValuesTheWayUsersWriteThem() {
        assertThat(PluginDocs.duration(Duration.ofDays(2))).isEqualTo("2d");
        assertThat(PluginDocs.duration(Duration.ofHours(3))).isEqualTo("3h");
        assertThat(PluginDocs.duration(Duration.ofMinutes(5))).isEqualTo("5m");
        assertThat(PluginDocs.duration(Duration.ofSeconds(90))).isEqualTo("90s");
        assertThat(PluginDocs.duration(Duration.ofMillis(250))).isEqualTo("250ms");
        assertThat(PluginDocs.duration(Duration.ZERO)).isEqualTo("0s");
        assertThat(PluginDocs.text(null)).isNull();
        assertThat(PluginDocs.text(true)).isEqualTo(true);
        assertThat(PluginDocs.text(List.of("a"))).isEqualTo(List.of("a"));
        assertThat(PluginDocs.text(Map.of())).isEqualTo(Map.of());
        assertThat(PluginDocs.text(java.nio.file.Path.of("x"))).isEqualTo("x");
    }

    @Test
    void componentsWithoutFactoriesHaveNoOptions() {
        PluginDescriptor descriptor = PluginInspectorTest.descriptor(
                "  - { type: dialect, identifier: generic, class: " + PluginInspectorTest.FIXTURES + "NotAFactory }\n");
        PluginInspector.Result result =
                PluginInspector.inspect(descriptor, getClass().getClassLoader());
        Map<String, Object> model = PluginDocs.model(
                PluginDescriptor.parse(
                                "id: x\nname: X\nversion: 1.0.0\napi-version: 1.x\nprovides: [{type: dialect, identifier: generic, class: a.B}]\n")
                        .orElseThrow(),
                result.components());
        assertThat(model).doesNotContainKey("datasource");
        @SuppressWarnings("unchecked")
        Map<String, Object> component = ((List<Map<String, Object>>) model.get("components")).get(0);
        assertThat(component).doesNotContainKey("options").containsEntry("type", "dialect");
    }
}
