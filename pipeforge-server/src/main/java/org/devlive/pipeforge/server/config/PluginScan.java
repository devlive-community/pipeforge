/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.config;

import java.nio.file.Path;
import java.util.Optional;
import org.devlive.pipeforge.plugin.DevPlugins;
import org.devlive.pipeforge.plugin.PluginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the plugins of the server: the packages in {@code pipeforge.plugin.paths} and, in
 * development mode, the plugin modules of the source checkout.
 */
final class PluginScan {

    private static final Logger LOG = LoggerFactory.getLogger(PluginScan.class);

    private PluginScan() {}

    /**
     * @param workingDirectory where the checkout root is searched from when none is configured
     */
    static PluginRepository.Scan scan(ServerProperties.Plugin settings, Path workingDirectory) {
        PluginRepository.Scan installed = new PluginRepository(settings.cache()).scan(settings.paths());
        ServerProperties.Dev dev = settings.dev();
        if (!dev.enabled()) {
            if (installed.plugins().isEmpty()) {
                LOG.warn("No plugins are installed. To use the plugins of a source checkout, start the server"
                        + " with the dev profile (--spring.profiles.active=dev) after ./mvnw compile");
            }
            return installed;
        }
        Path configured = dev.root();
        Optional<Path> root = configured != null ? Optional.of(configured) : DevPlugins.findRoot(workingDirectory);
        if (root.isEmpty()) {
            LOG.warn(
                    "Development mode is on, but {} is not inside a source checkout; set pipeforge.plugin.dev.root",
                    workingDirectory.toAbsolutePath().normalize());
            return installed;
        }
        LOG.info("Development mode: loading the plugin modules of {}", root.get());
        PluginRepository.Scan development = DevPlugins.scan(root.get());
        if (development.plugins().isEmpty() && development.rejected().isEmpty()) {
            LOG.warn("Development mode found no plugin module (*/*/{}) in {}", DevPlugins.DESCRIPTOR, root.get());
        }
        return DevPlugins.overlay(installed, development);
    }
}
