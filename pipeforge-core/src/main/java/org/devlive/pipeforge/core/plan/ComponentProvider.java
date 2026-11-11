/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import org.devlive.pipeforge.core.job.NodeKind;
import org.jspecify.annotations.Nullable;

/** Finds the component of a node. Closing the provider releases what it loaded. */
public interface ComponentProvider extends AutoCloseable {

    /**
     * Finds the component of a node.
     *
     * @param kind the node kind
     * @param type the component identifier, e.g. {@code fake}
     * @param plugin {@code id} or {@code id@version} of the plugin, or {@code null} for any
     * @throws org.devlive.pipeforge.common.error.PipeForgeException {@link
     *     org.devlive.pipeforge.common.error.ErrorCode#PLUGIN_NOT_FOUND} when no plugin provides it
     */
    Component resolve(NodeKind kind, String type, @Nullable String plugin);

    @Override
    default void close() {}
}
