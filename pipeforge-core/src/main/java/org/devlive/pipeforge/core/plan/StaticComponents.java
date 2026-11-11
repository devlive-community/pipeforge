/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.NodeKind;
import org.jspecify.annotations.Nullable;

/**
 * Components given as factory instances on the application class path, without plugin packages:
 * for tests and for embedding PipeForge. They are reported as plugin {@value #PLUGIN}.
 */
public final class StaticComponents implements ComponentProvider {

    /** The plugin name reported for static components. */
    public static final String PLUGIN = "builtin";

    private final Map<String, Factory> factories;

    private StaticComponents(Map<String, Factory> factories) {
        this.factories = Map.copyOf(factories);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Component resolve(NodeKind kind, String type, @Nullable String plugin) {
        Factory factory = factories.get(kind.id() + ":" + type);
        if (factory == null || (plugin != null && !plugin.equals(PLUGIN))) {
            throw PipeForgeException.of(ErrorCode.PLUGIN_NOT_FOUND)
                    .param("plugin", plugin == null ? type : plugin)
                    .param("type", kind.pluginType().id())
                    .build();
        }
        return new StaticComponent(factory);
    }

    private record StaticComponent(Factory factory) implements Component {
        @Override
        public String plugin() {
            return PLUGIN;
        }

        @Override
        public ClassLoader classLoader() {
            return factory.getClass().getClassLoader();
        }

        @Override
        public <T> T call(String operation, Callable<T> action) {
            try {
                return action.call();
            } catch (PipeForgeException e) {
                throw e;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw PipeForgeException.of(ErrorCode.PLUGIN_CALL_FAILED)
                        .param("plugin", PLUGIN)
                        .param("operation", operation)
                        .param(
                                "reason",
                                e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()))
                        .cause(e)
                        .build();
            }
        }
    }

    /** Builder of {@link StaticComponents}. */
    public static final class Builder {
        private final Map<String, Factory> factories = new LinkedHashMap<>();

        private Builder() {}

        public Builder source(SourceFactory factory) {
            return add(NodeKind.SOURCE, factory);
        }

        public Builder transform(TransformFactory factory) {
            return add(NodeKind.TRANSFORM, factory);
        }

        public Builder sink(SinkFactory factory) {
            return add(NodeKind.SINK, factory);
        }

        private Builder add(NodeKind kind, Factory factory) {
            if (factories.putIfAbsent(kind.id() + ":" + factory.identifier(), factory) != null) {
                throw new IllegalArgumentException("Two " + kind.id() + "s named " + factory.identifier());
            }
            return this;
        }

        public StaticComponents build() {
            return new StaticComponents(factories);
        }
    }
}
