/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import java.util.Locale;
import org.devlive.pipeforge.common.plugin.PluginType;

/** The three kinds of nodes, each written in its own list of the job. */
public enum NodeKind {
    SOURCE(PluginType.SOURCE),
    TRANSFORM(PluginType.TRANSFORM),
    SINK(PluginType.SINK);

    private final PluginType pluginType;

    NodeKind(PluginType pluginType) {
        this.pluginType = pluginType;
    }

    /** The key of the node list and of the env defaults, e.g. {@code source}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public PluginType pluginType() {
        return pluginType;
    }
}
