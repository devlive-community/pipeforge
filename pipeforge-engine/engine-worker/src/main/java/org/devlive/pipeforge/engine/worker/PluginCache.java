/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.ComponentProvider;
import org.devlive.pipeforge.core.plan.StaticComponents;
import org.devlive.pipeforge.engine.rpc.ChainCodec;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.plugin.InstalledPlugin;
import org.devlive.pipeforge.plugin.PluginLease;
import org.devlive.pipeforge.plugin.PluginManager;
import org.devlive.pipeforge.plugin.PluginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plugins of the runs on a worker. A deployment names its plugins with the SHA-256 of their
 * packages; a package the worker does not have is downloaded from the master once, checked and
 * kept in the cache directory, so later runs start without downloading. Each run gets its own
 * class loader per plugin, which is closed when the run is released.
 */
public final class PluginCache implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PluginCache.class);

    /** Downloads a blob, checking its hash; {@code BlobClient::fetch} on a cluster. */
    @FunctionalInterface
    public interface Fetcher {
        /**
         * Writes the blob {@code sha256} to {@code target}.
         *
         * @param name how errors name the blob
         */
        void fetch(String sha256, String name, Path target);
    }

    /** A plugin of a run: its own manager, so its class loader belongs to the run alone. */
    private record Loaded(PluginManager manager, PluginLease lease) {}

    private final Fetcher fetcher;
    private final Path blobs;
    private final PluginRepository extracted;
    private final ComponentProvider builtin;
    private final PluginManager.Options options;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Loaded>> runs = new HashMap<>();
    private int downloads;
    private final ClassLoaderLeaks leaks;

    /**
     * @param directory where packages and their extracted plugins are kept
     * @param builtin the components that come with the worker ({@value StaticComponents#PLUGIN})
     */
    public PluginCache(Fetcher fetcher, Path directory, ComponentProvider builtin, PluginManager.Options options) {
        this(fetcher, directory, builtin, options, new ClassLoaderLeaks());
    }

    /**
     * @param leaks follows the class loaders of released runs until they are collected; the caller
     *     checks it
     */
    public PluginCache(
            Fetcher fetcher,
            Path directory,
            ComponentProvider builtin,
            PluginManager.Options options,
            ClassLoaderLeaks leaks) {
        this.leaks = leaks;
        this.fetcher = fetcher;
        this.blobs = directory.resolve("blobs");
        this.extracted = new PluginRepository(directory.resolve("plugins"));
        this.builtin = builtin;
        this.options = options;
    }

    /**
     * The resolver of a run's components.
     *
     * @param plugins the plugins its deployment names, with the hashes of their packages
     */
    public ChainCodec.ComponentResolver forRun(String runId, List<PluginRef> plugins) {
        Map<String, PluginRef> refs = new HashMap<>();
        for (PluginRef ref : plugins) {
            refs.put(ref.getId() + "@" + ref.getVersion(), ref);
        }
        return (kind, type, plugin) -> {
            if (StaticComponents.PLUGIN.equals(plugin)) {
                return builtin.resolve(kind, type, null);
            }
            PluginRef ref = refs.get(plugin);
            if (ref == null || ref.getSha256().isEmpty()) {
                throw PipeForgeException.of(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE)
                        .param("plugin", plugin)
                        .param("reason", "the deployment names no package for it")
                        .build();
            }
            PluginLease lease = lease(runId, plugin, ref, refs);
            Factory factory = lease.factory(kind.pluginType(), type, factoryInterface(kind));
            return new Leased(factory, lease);
        };
    }

    /** Closes the class loaders of a run. */
    public void release(String runId) {
        Map<String, Loaded> loaded;
        synchronized (runs) {
            loaded = runs.remove(runId);
        }
        if (loaded == null) {
            return;
        }
        for (Loaded plugin : loaded.values()) {
            ClassLoader loader = plugin.lease().classLoader();
            plugin.lease().close();
            plugin.manager().close();
            leaks.watch(loader, plugin.lease().plugin().coordinate(), runId);
        }
        LOG.debug("Released the plugins of run {}", runId);
    }

    /** The class loaders of released runs, followed until they are collected. */
    public ClassLoaderLeaks leaks() {
        return leaks;
    }

    /** Packages downloaded since the cache was created. */
    public synchronized int downloads() {
        return downloads;
    }

    /** Number of plugin class loaders the runs hold. */
    public int openLoaders() {
        synchronized (runs) {
            return runs.values().stream().mapToInt(Map::size).sum();
        }
    }

    @Override
    public void close() {
        List<String> all;
        synchronized (runs) {
            all = List.copyOf(runs.keySet());
        }
        all.forEach(this::release);
    }

    private PluginLease lease(String runId, String coordinate, PluginRef ref, Map<String, PluginRef> refs) {
        synchronized (runs) {
            Loaded loaded = runs.computeIfAbsent(runId, r -> new HashMap<>()).get(coordinate);
            if (loaded != null) {
                return loaded.lease();
            }
        }
        InstalledPlugin plugin = install(coordinate, ref.getSha256());
        // the driver plugins it requires come with it in the deployment; a plugin that takes any
        // driver gets every driver plugin of the deployment
        List<InstalledPlugin> installed = new java.util.ArrayList<>(List.of(plugin));
        if (plugin.descriptor().anyDrivers()) {
            for (PluginRef other : refs.values()) {
                String id = other.getId() + "@" + other.getVersion();
                if (!id.equals(coordinate) && !other.getSha256().isEmpty()) {
                    InstalledPlugin candidate = install(id, other.getSha256());
                    if (candidate.descriptor().provides().stream()
                            .anyMatch(c -> c.type() == org.devlive.pipeforge.common.plugin.PluginType.DRIVER)) {
                        installed.add(candidate);
                    }
                }
            }
        }
        for (String driver : plugin.descriptor().drivers()) {
            refs.values().stream()
                    .filter(r -> r.getId().equals(driver) && !r.getSha256().isEmpty())
                    .findFirst()
                    .ifPresent(r -> installed.add(install(r.getId() + "@" + r.getVersion(), r.getSha256())));
        }
        PluginManager manager = new PluginManager(installed, options);
        Loaded created = new Loaded(manager, manager.acquire(plugin, "run:" + runId));
        Loaded kept;
        synchronized (runs) {
            kept = runs.computeIfAbsent(runId, r -> new HashMap<>()).putIfAbsent(coordinate, created);
        }
        if (kept != null) {
            created.lease().close();
            created.manager().close();
            return kept.lease();
        }
        return created.lease();
    }

    /** The extracted plugin of a package, downloaded first when the cache does not have it. */
    private InstalledPlugin install(String coordinate, String sha256) {
        synchronized (locks.computeIfAbsent(sha256, s -> new Object())) {
            Path zip = blobs.resolve(sha256 + ".zip");
            try {
                if (!Files.isRegularFile(zip)) {
                    fetcher.fetch(sha256, coordinate, zip);
                    synchronized (this) {
                        downloads++;
                    }
                    LOG.info("Downloaded the package of {} ({})", coordinate, sha256);
                }
                InstalledPlugin plugin = PluginRepository.read(extracted.extract(zip));
                if (!plugin.coordinate().equals(coordinate)) {
                    throw new IllegalArgumentException("the package holds " + plugin.coordinate());
                }
                return plugin;
            } catch (IOException | IllegalArgumentException e) {
                throw PipeForgeException.of(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE)
                        .param("plugin", coordinate)
                        .param("reason", "the package cannot be read: " + e.getMessage())
                        .cause(e)
                        .build();
            }
        }
    }

    private static Class<? extends Factory> factoryInterface(NodeKind kind) {
        return switch (kind) {
            case SOURCE -> SourceFactory.class;
            case TRANSFORM -> TransformFactory.class;
            case SINK -> SinkFactory.class;
        };
    }

    private record Leased(Factory factory, PluginLease lease) implements Component {
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
