/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.factory.JobContext;

/** Planning-time context of a source. */
@Stable
public interface SourceContext {

    JobContext job();

    /** The class loader of the plugin. */
    ClassLoader classLoader();
}
