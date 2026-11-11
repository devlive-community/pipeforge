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
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.plugin.InstalledPlugin;
import org.devlive.pipeforge.plugin.PluginLease;
import org.devlive.pipeforge.plugin.PluginManager;
import org.jspecify.annotations.Nullable;

/**
 * Components from installed plugin packages. Each plugin version is leased once for the owner
 * (usually the job) and released when this provider is closed.
 */
public final class PluginComponents implements ComponentProvider {

    private final PluginManager manager;
    private final String owner;
    private final Map<String, PluginLease> leases = new LinkedHashMap<>();

    public PluginComponents(PluginManager manager, String owner) {
        this.manager = manager;
        this.owner = owner;
    }

    @Override
    public synchronized Component resolve(NodeKind kind, String type, @Nullable String plugin) {
        InstalledPlugin installed = manager.resolve(kind.pluginType(), type, plugin);
        PluginLease lease = leases.computeIfAbsent(installed.coordinate(), c -> manager.acquire(installed, owner));
        Factory factory = lease.factory(kind.pluginType(), type, factoryInterface(kind));
        return new LeasedComponent(factory, lease);
    }

    private static Class<? extends Factory> factoryInterface(NodeKind kind) {
        return switch (kind) {
            case SOURCE -> SourceFactory.class;
            case TRANSFORM -> TransformFactory.class;
            case SINK -> SinkFactory.class;
        };
    }

    @Override
    public synchronized void close() {
        leases.values().forEach(PluginLease::close);
        leases.clear();
    }

    private record LeasedComponent(Factory factory, PluginLease lease) implements Component {
        @Override
        public String plugin() {
            return lease.plugin().coordinate();
        }

        @Override
        public ClassLoader classLoader() {
            return lease.classLoader();
        }

        @Override
        public <T> T call(String operation, Callable<T> action) {
            return lease.invoker().call(operation, action);
        }
    }
}
