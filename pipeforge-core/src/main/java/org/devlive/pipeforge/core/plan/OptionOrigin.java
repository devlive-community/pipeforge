/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.util.Locale;

/** Where the value of a node option comes from, from the highest priority to the lowest. */
public enum OptionOrigin {
    /** Written on the node. */
    NODE,
    /** A default for all nodes of the kind in the job's env, e.g. env.source.fetch-size. */
    ENV,
    /** A default for all nodes of the kind in the cluster configuration. */
    CLUSTER,
    /** The default value declared by the plugin. */
    DEFAULT;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
