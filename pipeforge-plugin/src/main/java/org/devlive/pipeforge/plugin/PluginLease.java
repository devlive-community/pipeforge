/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.devlive.pipeforge.common.plugin.PluginType;

/**
 * One user's hold on a plugin version: its class loader, its invoker and the factories created
 * from it. Closing the lease releases the hold; the loader is closed when no lease is left.
 * Objects created through a lease must not be used after it is closed.
 */
public final class PluginLease implements AutoCloseable {

    private final PluginManager manager;
    private final InstalledPlugin plugin;
    private final PluginClassLoader loader;
    private final PluginInvoker invoker;
    private final String owner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Factory> factories = new HashMap<>();

    PluginLease(
            PluginManager manager,
            InstalledPlugin plugin,
            PluginClassLoader loader,
            PluginInvoker invoker,
            String owner) {
        this.manager = manager;
        this.plugin = plugin;
        this.loader = loader;
        this.invoker = invoker;
        this.owner = owner;
    }

    public InstalledPlugin plugin() {
        return plugin;
    }

    public ClassLoader classLoader() {
        return loader;
    }

    public PluginInvoker invoker() {
        return invoker;
    }

    /** The job or component that holds the lease. */
    public String owner() {
        return owner;
    }

    /**
     * The factory of a component, created on first use through the invoker.
     *
     * @param expected the factory interface, e.g. {@code SourceFactory.class}
     */
    public synchronized <F extends Factory> F factory(PluginType type, String identifier, Class<F> expected) {
        if (closed.get()) {
            throw new IllegalStateException("The lease of " + plugin.coordinate() + " is closed");
        }
        PluginDescriptor.Component component = PluginManager.component(plugin, type, identifier);
        Factory factory = factories.get(type.id() + ":" + identifier);
        if (factory == null) {
            factory = invoker.call("create factory " + identifier, () -> {
                Class<?> clazz = Class.forName(component.className(), true, loader);
                if (!expected.isAssignableFrom(clazz)) {
                    throw PluginManager.loadFailed(
                            plugin, component.className() + " does not implement " + expected.getSimpleName(), null);
                }
                Factory created = (Factory) clazz.getConstructor().newInstance();
                if (!created.identifier().equals(identifier)) {
                    throw PluginManager.loadFailed(
                            plugin,
                            component.className() + " reports identifier '" + created.identifier() + "' instead of '"
                                    + identifier + "'",
                            null);
                }
                return created;
            });
            factories.put(type.id() + ":" + identifier, factory);
        }
        return expected.cast(factory);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            synchronized (this) {
                factories.clear();
            }
            manager.release(plugin);
        }
    }
}
