/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.factory;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.source.Source;

/** Creates {@link Source} instances. */
@Stable
public interface SourceFactory extends Factory {

    Source<?, ?> createSource(FactoryContext context) throws Exception;
}
