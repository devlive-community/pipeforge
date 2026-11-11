/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Sink capability: Applies schema changes with {@code SinkWriter#applySchemaChange}. */
@Evolving
public interface SupportsSchemaEvolution {
    /** Schema change types the sink can apply. */
    java.util.Set<Class<? extends org.devlive.pipeforge.api.table.SchemaChangeEvent>> supportedSchemaChanges();
}
