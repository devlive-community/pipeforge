/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PluginDescriptorTest {

    private static final String VALID =
            """
            id: mysql
            name: MySQL
            version: 1.0.0
            api-version: "1.x"
            vendor: PipeForge
            license: MIT
            description: Reads and writes MySQL.
            provides:
              - { type: source, identifier: jdbc-mysql, class: org.example.MySqlSourceFactory }
              - { type: sink, identifier: jdbc-mysql, class: org.example.MySqlSinkFactory }
            datasource:
              type: mysql
              versions: ["5.7", "8.0", 8.4]
            requires:
              drivers: [mysql-connector-j]
            lob: streaming
            """;

    @Test
    void parsesAValidDescriptor() {
        PluginDescriptor descriptor = PluginDescriptor.parse(VALID).orElseThrow();
        assertThat(descriptor.id()).isEqualTo("mysql");
        assertThat(descriptor.coordinate()).isEqualTo("mysql@1.0.0");
        assertThat(descriptor.apiMajorVersion()).isEqualTo(1);
        assertThat(descriptor.vendor()).isEqualTo("PipeForge");
        assertThat(descriptor.license()).isEqualTo("MIT");
        assertThat(descriptor.description()).isEqualTo("Reads and writes MySQL.");
        assertThat(descriptor.provides())
                .containsExactly(
                        new PluginDescriptor.Component(
                                PluginType.SOURCE, "jdbc-mysql", "org.example.MySqlSourceFactory"),
                        new PluginDescriptor.Component(PluginType.SINK, "jdbc-mysql", "org.example.MySqlSinkFactory"));
        assertThat(descriptor.datasource())
                .isEqualTo(new PluginDescriptor.Datasource("mysql", List.of("5.7", "8.0", "8.4")));
        assertThat(descriptor.drivers()).containsExactly("mysql-connector-j");
        assertThat(descriptor.extra()).containsEntry("lob", "streaming");
    }

    @Test
    void reportsEveryProblem() {
        PluginDescriptor.ParseResult result = PluginDescriptor.parse(
                """
                id: My_Plugin
                version: one
                api-version: "1"
                provides:
                  - { type: gadget, identifier: x, class: Gadget }
                  - { type: source, identifier: Bad Id, class: org.example.A }
                  - just text
                  - { type: sink, identifier: a, class: org.example.A }
                  - { type: sink, identifier: a, class: org.example.B }
                datasource: [x]
                requires: { drivers: one }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.descriptor()).isNull();
        assertThat(result.problems())
                .containsExactlyInAnyOrder(
                        "id must be lower case words joined by '-': My_Plugin",
                        "name is required",
                        "version must be a semantic version such as 1.0.0: one",
                        "api-version must name an API major version such as \"1.x\": 1",
                        "provides[1] has unknown type 'gadget'",
                        "provides[1] class is not a fully qualified class name: Gadget",
                        "provides[2] identifier must be lower case words joined by '-': Bad Id",
                        "provides[3] must be a mapping with type, identifier and class",
                        "provides[5] repeats sink 'a'",
                        "datasource must be a mapping",
                        "requires.drivers must be a list");
        assertThatThrownBy(result::orElseThrow).hasMessageStartingWith("Invalid plugin.yaml: id must be");
    }

    @Test
    void rejectsBrokenDocuments() {
        assertThat(PluginDescriptor.parse("id: [").problems())
                .singleElement()
                .asString()
                .startsWith("not valid YAML");
        assertThat(PluginDescriptor.parse("- a").problems()).containsExactly("plugin.yaml must be a mapping");
        assertThat(PluginDescriptor.parse("id: x\nname: X\nversion: 1.0.0\napi-version: 1.x\n")
                        .problems())
                .containsExactly("provides must list at least one component");
        assertThat(PluginDescriptor.parse("id: x\nname: {a: 1}\nversion: 1.0.0\napi-version: 1.x\nprovides: []\n")
                        .problems())
                .contains("name must be a plain value");
        assertThat(PluginDescriptor.parse("id: x\nname: ''\nversion: 1.0.0\napi-version: 1.x\nprovides: []\n")
                        .problems())
                .contains("name must not be empty");
        assertThat(PluginDescriptor.parse(VALID.replace("requires:\n  drivers: [mysql-connector-j]", "requires: x"))
                        .problems())
                .containsExactly("requires must be a mapping");
        assertThat(PluginDescriptor.parse(VALID.replace("versions: [\"5.7\", \"8.0\", 8.4]", "versions: 8.0"))
                        .problems())
                .containsExactly("datasource.versions must be a list");
    }

    @Test
    void typesKnowTheirFactoryInterfaces() {
        assertThat(PluginType.parse("transform")).contains(PluginType.TRANSFORM);
        assertThat(PluginType.parse("TRANSFORM")).isEmpty();
        assertThat(PluginType.SOURCE.factoryInterface()).contains("org.devlive.pipeforge.api.factory.SourceFactory");
        assertThat(PluginType.DRIVER.factoryInterface()).isEmpty();
        assertThat(PluginType.FUNCTION.id()).isEqualTo("function");
    }

    @Test
    void optionalPartsMayBeAbsent() {
        PluginDescriptor descriptor = PluginDescriptor.parse(
                        """
                id: fake
                name: Fake
                version: 0.1.0-SNAPSHOT
                api-version: 0.x
                provides: [{type: source, identifier: fake, class: org.example.Fake}]
                requires: {}
                """)
                .orElseThrow();
        assertThat(descriptor.datasource()).isNull();
        assertThat(descriptor.drivers()).isEmpty();
        assertThat(descriptor.apiMajorVersion()).isZero();
    }
}
