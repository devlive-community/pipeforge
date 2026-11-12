/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.devlive.pipeforge.common.plugin.PluginType;
import org.devlive.pipeforge.plugin.check.fixture.Fixtures;
import org.junit.jupiter.api.Test;

class PluginInspectorTest {

    static final String FIXTURES = "org.devlive.pipeforge.plugin.check.fixture.Fixtures$";

    static PluginDescriptor descriptor(String provides) {
        return PluginDescriptor.parse(
                        """
                id: sample
                name: Sample
                version: 0.1.0
                api-version: 0.x
                datasource: { type: sample, versions: ["1.0"] }
                provides:
                """
                                + provides)
                .orElseThrow();
    }

    @Test
    void inspectsAValidFactory() {
        PluginInspector.Result result = PluginInspector.inspect(
                descriptor("  - { type: source, identifier: good, class: " + FIXTURES + "GoodSourceFactory }\n"),
                getClass().getClassLoader());
        assertThat(result.problems()).isEmpty();
        assertThat(result.components()).singleElement().satisfies(c -> {
            assertThat(c.rule()).isNotNull();
            assertThat(c.capabilities()).containsExactly("SupportsLimitPushDown", "SupportsRowCount");
        });
    }

    @Test
    void reportsEveryKindOfBrokenComponent() {
        PluginInspector.Result result = PluginInspector.inspect(
                descriptor(
                        """
                          - { type: source, identifier: missing, class: org.example.Missing }
                          - { type: source, identifier: plain, class: %1$sNotAFactory }
                          - { type: transform, identifier: renamed, class: %1$sWrongIdFactory }
                          - { type: source, identifier: broken, class: %1$sBrokenFactory }
                          - { type: source, identifier: failing, class: %1$sFailingRuleFactory }
                          - { type: source, identifier: hidden, class: %1$sHiddenFactory }
                          - { type: dialect, identifier: generic, class: %1$sNotAFactory }
                        """
                                .formatted(FIXTURES)),
                getClass().getClassLoader());
        assertThat(result.problems())
                .containsExactly(
                        "source 'missing': class org.example.Missing not found",
                        "source 'plain': " + FIXTURES + "NotAFactory does not implement SourceFactory",
                        "transform 'renamed': " + FIXTURES
                                + "WrongIdFactory.identifier() returns 'something-else' but plugin.yaml declares 'renamed'",
                        "source 'broken': cannot create " + FIXTURES
                                + "BrokenFactory with its public no-argument constructor: IllegalStateException: cannot start",
                        "source 'failing': " + FIXTURES
                                + "FailingRuleFactory fails to describe itself: IllegalArgumentException: bad rule",
                        "source 'hidden': " + FIXTURES + "HiddenFactory must be a public, concrete class");
        assertThat(result.components())
                .extracting(c -> c.component().identifier())
                .containsExactly("renamed", "generic");
    }

    @Test
    void findsNoCapabilitiesWithoutACreateMethod() {
        assertThat(PluginInspector.capabilities(Fixtures.NotAFactory.class, PluginType.SOURCE))
                .isEmpty();
        assertThat(PluginInspector.capabilities(Fixtures.GoodSourceFactory.class, PluginType.DIALECT))
                .isEmpty();
        assertThat(PluginInspector.capabilities(Fixtures.WrongIdFactory.class, PluginType.TRANSFORM))
                .isEmpty();
        assertThat(List.of(Fixtures.class.getDeclaredClasses())).isNotEmpty();
    }
}
