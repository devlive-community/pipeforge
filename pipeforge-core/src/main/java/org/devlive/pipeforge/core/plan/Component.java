/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.util.concurrent.Callable;
import org.devlive.pipeforge.api.factory.Factory;

/** A component resolved for a node: its factory and the plugin that provides it. */
public interface Component {

    Factory factory();

    /** {@code id@version} of the providing plugin, or {@code builtin}. */
    String plugin();

    ClassLoader classLoader();

    /**
     * Runs plugin code with the plugin's context class loader. {@link
     * org.devlive.pipeforge.common.error.PipeForgeException}s pass through; other exceptions become
     * {@link org.devlive.pipeforge.common.error.ErrorCode#PLUGIN_CALL_FAILED}.
     */
    <T> T call(String operation, Callable<T> action);
}
