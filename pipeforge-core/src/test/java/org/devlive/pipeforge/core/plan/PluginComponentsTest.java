/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.devlive.pipeforge.api.ApiVersion;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.core.Errors;
import org.devlive.pipeforge.core.TestComponents;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plans against an installed plugin package whose factories come from the test class path. */
class PluginComponentsTest {

    /** A source factory with a public no-argument constructor, as plugins provide them. */
    public static final class FakeSource implements SourceFactory {
        private final SourceFactory delegate = TestComponents.sourceFactory("fake", Boundedness.BOUNDED);

        @Override
        public String identifier() {
            return "fake";
        }

        @Override
        public OptionRule optionRule() {
            return delegate.optionRule();
        }

        @Override
        public Source<?, ?> createSource(FactoryContext context) throws Exception {
            return delegate.createSource(context);
        }
    }

    /** A sink factory with a public no-argument constructor. */
    public static final class ConsoleSink implements SinkFactory {
        private final SinkFactory delegate = TestComponents.sinkFactory("console");

        @Override
        public String identifier() {
            return "console";
        }

        @Override
        public OptionRule optionRule() {
            return delegate.optionRule();
        }

        @Override
        public Sink<?, ?> createSink(FactoryContext context) throws Exception {
            return delegate.createSink(context);
        }
    }

    @Test
    void resolvesComponentsFromPluginsAndReleasesThem(@TempDir Path dir) throws IOException {
        Path plugin = Files.createDirectories(
                dir.resolve("plugins").resolve("demo-1.2.0").resolve("lib"));
        try (OutputStream out = Files.newOutputStream(plugin.resolve("demo.jar"));
                JarOutputStream jar = new JarOutputStream(out, new Manifest())) {
            jar.flush();
        }
        Files.writeString(
                plugin.getParent().resolve("plugin.yaml"),
                """
                id: demo
                name: Demo
                version: 1.2.0
                api-version: "%s"
                provides:
                  - { type: source, identifier: fake, class: %s }
                  - { type: sink, identifier: console, class: %s }
                """
                        .formatted(
                                ApiVersion.descriptorForm(), FakeSource.class.getName(), ConsoleSink.class.getName()));

        try (PluginManager manager = PluginManager.load(
                List.of(dir.resolve("plugins")), dir.resolve("cache"), PluginManager.Options.defaults())) {
            String yaml =
                    "version: 1\nname: p\nsource: [{ id: s, type: fake, plugin: demo }]\nsink: [{ id: k, type: console }]";
            try (PluginComponents components = new PluginComponents(manager, "p")) {
                JobPlan plan = new Planner(components).plan(TestComponents.parse(yaml));
                assertThat(plan.nodes()).extracting(PlannedNode::plugin).containsExactly("demo@1.2.0", "demo@1.2.0");
                assertThat(plan.node("s")
                                .orElseThrow()
                                .component()
                                .classLoader()
                                .getName())
                        .isEqualTo("plugin:demo@1.2.0");
                assertThat(manager.openLoaders()).isEqualTo(1);
                assertThat(Errors.message(
                                ErrorCode.PLUGIN_NOT_FOUND, () -> components.resolve(NodeKind.TRANSFORM, "fake", null)))
                        .contains("fake");
            }
            assertThat(manager.openLoaders()).isZero();
        }
    }
}
