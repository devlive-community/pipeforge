/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.StaticComponents;
import org.devlive.pipeforge.engine.rpc.ChainCodec;
import org.devlive.pipeforge.engine.rpc.grpc.BlobServer;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.plugin.InstalledPlugin;
import org.devlive.pipeforge.plugin.PluginManager;
import org.devlive.pipeforge.plugin.PluginPackage;

/**
 * The packages of the master's plugins, for workers: each plugin is packed once, when a deployment
 * first names it, and is served by its SHA-256.
 */
public final class PluginBlobs implements BlobServer.Source {

    private final PluginManager plugins;
    private final Path directory;
    private final Map<String, PluginPackage.Packed> packed = new ConcurrentHashMap<>();
    private final Map<String, Path> bySha = new ConcurrentHashMap<>();

    /** @param directory where the packages are written */
    public PluginBlobs(PluginManager plugins, Path directory) {
        this.plugins = plugins;
        this.directory = directory;
    }

    /**
     * The reference of a plugin coordinate ({@code id@version}) with the hash of its package; a
     * builtin component has no package.
     */
    public PluginRef ref(String coordinate) {
        PluginRef ref = ChainCodec.pluginRef(coordinate);
        if (StaticComponents.PLUGIN.equals(coordinate)) {
            return ref;
        }
        return ref.toBuilder().setSha256(pack(coordinate).sha256()).build();
    }

    @Override
    public Optional<Path> find(String sha256) {
        return Optional.ofNullable(bySha.get(sha256));
    }

    private PluginPackage.Packed pack(String coordinate) {
        return packed.computeIfAbsent(coordinate, c -> {
            InstalledPlugin plugin = plugins.plugins().stream()
                    .filter(p -> p.coordinate().equals(c))
                    .findFirst()
                    .orElseThrow(() -> PipeForgeException.of(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE)
                            .param("plugin", c)
                            .param("reason", "the master has no such plugin")
                            .build());
            try {
                PluginPackage.Packed result = PluginPackage.pack(plugin, directory);
                bySha.put(result.sha256(), result.file());
                return result;
            } catch (IOException e) {
                throw PipeForgeException.of(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE)
                        .param("plugin", c)
                        .param("reason", "packing failed: " + e.getMessage())
                        .cause(e)
                        .build();
            }
        });
    }
}
