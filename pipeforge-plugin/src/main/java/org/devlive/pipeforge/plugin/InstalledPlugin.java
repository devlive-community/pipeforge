/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.nio.file.Path;
import java.util.List;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;

/**
 * A plugin found in a plugin directory or, in development mode, in a source tree: its descriptor,
 * its root directory and its class path.
 *
 * @param descriptor the parsed plugin.yaml
 * @param root the extracted plugin directory, or the source module of a development plugin
 * @param classpath the jars of lib/ sorted by name; for a development plugin, its classes
 *     directory followed by the jars of its dependencies
 */
public record InstalledPlugin(PluginDescriptor descriptor, Path root, List<Path> classpath) {

    public InstalledPlugin {
        classpath = List.copyOf(classpath);
    }

    public String id() {
        return descriptor.id();
    }

    public String version() {
        return descriptor.version();
    }

    /** {@code id@version}. */
    public String coordinate() {
        return descriptor.coordinate();
    }
}
