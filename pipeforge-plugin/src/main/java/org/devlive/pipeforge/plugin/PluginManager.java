/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.devlive.pipeforge.api.ApiVersion;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorRegistry;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.devlive.pipeforge.common.plugin.PluginType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Knows the installed plugins and hands out their class loaders.
 *
 * <p>Several versions of a plugin can be installed at once; a job picks one with {@code
 * plugin: id@version}, otherwise the highest version wins. Each plugin version gets one {@link
 * PluginClassLoader} that is shared by all its current users ({@link PluginLease}s) and closed when
 * the last lease is released, so that it can be garbage collected.
 *
 * <p>Before a class loader is created, the plugin's jars are checked for calls that change the
 * whole JVM ({@link ForbiddenCalls}); such a plugin is refused.
 */
public final class PluginManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PluginManager.class);

    /** Loading options. */
    public record Options(ClassLoader parent, List<String> allowedCallerPackages) {
        public Options {
            allowedCallerPackages = List.copyOf(allowedCallerPackages);
        }

        /** The engine's class loader as parent, no trusted callers. */
        public static Options defaults() {
            return new Options(PluginManager.class.getClassLoader(), List.of());
        }
    }

    private final Map<String, InstalledPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, String> unavailable = new LinkedHashMap<>();
    private final Options options;
    private final Map<String, Shared> loaded = new HashMap<>();

    /** A loader shared by the leases of one plugin version. Guarded by the manager. */
    private static final class Shared {
        final PluginClassLoader loader;
        final PluginInvoker invoker;
        int leases;

        Shared(PluginClassLoader loader, PluginInvoker invoker) {
            this.loader = loader;
            this.invoker = invoker;
        }
    }

    public PluginManager(List<InstalledPlugin> installed, Options options) {
        this.options = options;
        int apiMajor = ApiVersion.major();
        for (InstalledPlugin plugin : installed) {
            String coordinate = plugin.coordinate();
            if (plugins.containsKey(coordinate)) {
                unavailable.put(coordinate, "installed twice");
                continue;
            }
            if (plugin.descriptor().apiMajorVersion() != apiMajor) {
                unavailable.put(
                        coordinate,
                        "built for API " + plugin.descriptor().apiVersion() + ", this engine provides "
                                + ApiVersion.descriptorForm());
                continue;
            }
            plugins.put(coordinate, plugin);
        }
    }

    /** Scans plugin directories and creates a manager; rejected entries are logged and listed as unavailable. */
    public static PluginManager load(List<Path> directories, Path cacheDir, Options options) {
        return load(new PluginRepository(cacheDir).scan(directories), options);
    }

    /**
     * Creates a manager from scanned plugins. The searched directories are logged with what was
     * found in them; rejected entries are logged and listed as unavailable.
     */
    public static PluginManager load(PluginRepository.Scan scan, Options options) {
        for (PluginRepository.Directory directory : scan.directories()) {
            if (directory.exists()) {
                LOG.info("Found {} plugin(s) in {}", directory.plugins(), directory.path());
            } else {
                LOG.info("Plugin directory {} does not exist", directory.path());
            }
        }
        if (scan.plugins().isEmpty()) {
            LOG.warn(
                    "No plugins found; searched {}",
                    scan.directories().stream().map(d -> d.path().toString()).toList());
        }
        PluginManager manager = new PluginManager(scan.plugins(), options);
        for (PluginRepository.Rejected rejected : scan.rejected()) {
            LOG.warn("Ignoring plugin {}: {}", rejected.location(), rejected.reason());
            manager.unavailable.put(rejected.location().toString(), rejected.reason());
        }
        return manager;
    }

    /** Available plugins, in installation order. */
    public List<InstalledPlugin> plugins() {
        return List.copyOf(plugins.values());
    }

    /** Plugins or entries that cannot be used, with the reason. */
    public Map<String, String> unavailable() {
        return Map.copyOf(unavailable);
    }

    /**
     * Finds the plugin that provides a component.
     *
     * @param reference {@code id} or {@code id@version} of the plugin, or {@code null} for any
     * @throws PipeForgeException {@link ErrorCode#PLUGIN_NOT_FOUND} or {@link ErrorCode#COMPONENT_AMBIGUOUS}
     */
    public InstalledPlugin resolve(PluginType type, String identifier, @Nullable String reference) {
        String id = reference == null ? null : reference.split("@", 2)[0];
        String version =
                reference != null && reference.contains("@") ? reference.split("@", 2)[1] : null;
        List<InstalledPlugin> candidates = new ArrayList<>();
        for (InstalledPlugin plugin : plugins.values()) {
            boolean provides = plugin.descriptor().provides().stream()
                    .anyMatch(c -> c.type() == type && c.identifier().equals(identifier));
            if (provides
                    && (id == null || plugin.id().equals(id))
                    && (version == null || plugin.version().equals(version))) {
                candidates.add(plugin);
            }
        }
        if (candidates.isEmpty()) {
            throw PipeForgeException.of(ErrorCode.PLUGIN_NOT_FOUND)
                    .param("plugin", reference == null ? identifier : reference)
                    .param("type", type.id())
                    .build();
        }
        Set<String> ids = new TreeSet<>();
        candidates.forEach(p -> ids.add(p.id()));
        if (ids.size() > 1) {
            throw PipeForgeException.of(ErrorCode.COMPONENT_AMBIGUOUS)
                    .param("type", type.id())
                    .param("identifier", identifier)
                    .param("plugins", String.join(", ", ids))
                    .build();
        }
        return candidates.stream()
                .max(Comparator.comparing(InstalledPlugin::version, PluginManager::compareVersions))
                .orElseThrow();
    }

    /**
     * Acquires the class loader of a plugin version for an owner (usually a job). The loader is
     * created on first use and closed when the last lease is closed.
     */
    public synchronized PluginLease acquire(InstalledPlugin plugin, String owner) {
        Shared shared = loaded.get(plugin.coordinate());
        if (shared == null) {
            checkForbiddenCalls(plugin);
            PluginClassLoader loader = new PluginClassLoader(plugin.coordinate(), urls(plugin), options.parent());
            ErrorRegistry.include(loader);
            shared = new Shared(loader, new PluginInvoker(plugin.coordinate(), loader));
            loaded.put(plugin.coordinate(), shared);
            LOG.info("Loaded plugin {} for {}", plugin.coordinate(), owner);
        }
        shared.leases++;
        return new PluginLease(this, plugin, shared.loader, shared.invoker, owner);
    }

    synchronized void release(InstalledPlugin plugin) {
        Shared shared = loaded.get(plugin.coordinate());
        if (shared == null) {
            return;
        }
        shared.leases--;
        if (shared.leases <= 0) {
            loaded.remove(plugin.coordinate());
            shared.invoker.close();
            try {
                shared.loader.close();
            } catch (IOException e) {
                LOG.warn("Closing the class loader of {} failed", plugin.coordinate(), e);
            }
            LOG.info("Unloaded plugin {}", plugin.coordinate());
        }
    }

    /** Number of plugin class loaders currently open. */
    public synchronized int openLoaders() {
        return loaded.size();
    }

    private void checkForbiddenCalls(InstalledPlugin plugin) {
        for (Path jar : plugin.classpath()) {
            List<ForbiddenCalls.Violation> violations;
            try {
                violations = ForbiddenCalls.scan(jar, options.allowedCallerPackages());
            } catch (IOException e) {
                throw loadFailed(plugin, "cannot read " + jar.getFileName() + ": " + e.getMessage(), e);
            }
            if (!violations.isEmpty()) {
                ForbiddenCalls.Violation first = violations.get(0);
                throw PipeForgeException.of(ErrorCode.PLUGIN_FORBIDDEN_CALL)
                        .param("plugin", plugin.coordinate())
                        .param("call", first.call())
                        .param("location", first.location())
                        .build();
            }
        }
    }

    private static URL[] urls(InstalledPlugin plugin) {
        List<URL> urls = new ArrayList<>();
        for (Path jar : plugin.classpath()) {
            try {
                urls.add(jar.toUri().toURL());
            } catch (MalformedURLException e) {
                throw loadFailed(plugin, "invalid jar path " + jar, e);
            }
        }
        return urls.toArray(URL[]::new);
    }

    static PipeForgeException loadFailed(InstalledPlugin plugin, String reason, @Nullable Throwable cause) {
        return PipeForgeException.of(ErrorCode.PLUGIN_LOAD_FAILED)
                .param("plugin", plugin.coordinate())
                .param("reason", reason)
                .cause(cause)
                .build();
    }

    /** Compares semantic versions; a pre-release sorts before its release. */
    static int compareVersions(String a, String b) {
        String[] mainA = a.split("[-+]", 2);
        String[] mainB = b.split("[-+]", 2);
        String[] partsA = mainA[0].split("\\.", -1);
        String[] partsB = mainB[0].split("\\.", -1);
        for (int i = 0; i < Math.max(partsA.length, partsB.length); i++) {
            long x = i < partsA.length ? parse(partsA[i]) : 0;
            long y = i < partsB.length ? parse(partsB[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        boolean preA = mainA.length > 1;
        boolean preB = mainB.length > 1;
        if (preA != preB) {
            return preA ? -1 : 1;
        }
        return preA ? mainA[1].compareTo(mainB[1]) : 0;
    }

    private static long parse(String part) {
        try {
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Finds the component entry of a plugin. */
    static PluginDescriptor.Component component(InstalledPlugin plugin, PluginType type, String identifier) {
        return plugin.descriptor().provides().stream()
                .filter(c -> c.type() == type && c.identifier().equals(identifier))
                .findFirst()
                .orElseThrow(() -> PipeForgeException.of(ErrorCode.PLUGIN_NOT_FOUND)
                        .param("plugin", plugin.coordinate() + "/" + identifier)
                        .param("type", type.id())
                        .build());
    }

    /** Closes every loader regardless of leases. */
    @Override
    public synchronized void close() {
        for (Map.Entry<String, Shared> entry : new ArrayList<>(loaded.entrySet())) {
            entry.getValue().invoker.close();
            try {
                entry.getValue().loader.close();
            } catch (IOException e) {
                LOG.warn("Closing the class loader of {} failed", entry.getKey(), e);
            }
        }
        loaded.clear();
    }
}
