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
 * A plugin found in a plugin directory: its descriptor, its root directory and the jars in lib/.
 *
 * @param descriptor the parsed plugin.yaml
 * @param root the extracted plugin directory
 * @param jars the jars of lib/, sorted by name
 */
public record InstalledPlugin(PluginDescriptor descriptor, Path root, List<Path> jars) {

    public InstalledPlugin {
        jars = List.copyOf(jars);
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
