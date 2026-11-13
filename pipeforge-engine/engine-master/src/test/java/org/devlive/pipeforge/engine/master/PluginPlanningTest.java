/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.ComponentProvider;
import org.devlive.pipeforge.plugin.PluginManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginPlanningTest {

    record Fake(String plugin) implements Component {
        @Override
        public Factory factory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassLoader classLoader() {
            return PluginPlanningTest.class.getClassLoader();
        }

        @Override
        public <T> T call(String operation, Callable<T> action) {
            throw new UnsupportedOperationException();
        }
    }

    /** Has connector-fake 1.0 and 2.0, and the builtin field mapper; 2.0 is the newest. */
    static final class Installed implements ComponentProvider {
        final List<String> asked = new CopyOnWriteArrayList<>();
        boolean closed;

        @Override
        public Component resolve(NodeKind kind, String type, @Nullable String plugin) {
            asked.add(String.valueOf(plugin));
            if ("field-mapper".equals(type)) {
                return new Fake("builtin");
            }
            if (plugin == null || plugin.equals("connector-fake")) {
                return new Fake("connector-fake@2.0");
            }
            if (plugin.equals("connector-fake@1.0") || plugin.equals("connector-fake@2.0")) {
                return new Fake(plugin);
            }
            throw PipeForgeException.of(ErrorCode.PLUGIN_NOT_FOUND)
                    .param("plugin", plugin)
                    .param("type", kind.id())
                    .build();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void takesComponentsFromThePinnedVersion() {
        Installed installed = new Installed();
        var pinned = new PluginPlanning.Pinned(installed, Map.of("connector-fake", "1.0"));
        assertThat(pinned.resolve(NodeKind.SOURCE, "fake", null).plugin()).isEqualTo("connector-fake@1.0");
        assertThat(installed.asked).containsExactly("null", "connector-fake@1.0");
        assertThat(pinned.resolve(NodeKind.TRANSFORM, "field-mapper", null).plugin())
                .isEqualTo("builtin");
        pinned.close();
        assertThat(installed.closed).isTrue();
    }

    @Test
    void keepsTheResolvedVersionWhenItIsPinnedOrNotPinned() {
        Installed installed = new Installed();
        assertThat(new PluginPlanning.Pinned(installed, Map.of("connector-fake", "2.0"))
                        .resolve(NodeKind.SOURCE, "fake", null)
                        .plugin())
                .isEqualTo("connector-fake@2.0");
        assertThat(new PluginPlanning.Pinned(installed, Map.of())
                        .resolve(NodeKind.SOURCE, "fake", null)
                        .plugin())
                .isEqualTo("connector-fake@2.0");
        assertThat(installed.asked).containsExactly("null", "null");
    }

    @Test
    void aPinnedVersionTheMasterDoesNotHaveFailsTheRun() {
        var pinned = new PluginPlanning.Pinned(new Installed(), Map.of("connector-fake", "3.0"));
        assertThatThrownBy(() -> pinned.resolve(NodeKind.SOURCE, "fake", null))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PLUGIN_VERSION_MISMATCH);
                    assertThat(e.getMessage()).contains("The run needs connector-fake 3.0, but the master has 2.0");
                    assertThat(e.getCause()).isInstanceOf(PipeForgeException.class);
                });
    }

    @Test
    void plansWithThePluginsOfTheMaster(@TempDir Path cache) {
        PluginManager none = PluginManager.load(List.of(), cache, PluginManager.Options.defaults());
        PluginPlanning planning = new PluginPlanning(none);
        String job =
                """
                version: 1
                name: no-plugins
                source:
                  - { id: src, type: fake, rows: 3 }
                sink:
                  - { id: out, type: console }
                """;
        assertThatThrownBy(() -> planning.plan(new Dispatcher.Submission(
                        "job", "run", job, Map.of("day", "2026-11-13"), java.time.Instant.EPOCH, Map.of(), null)))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PLUGIN_NOT_FOUND));
    }
}
