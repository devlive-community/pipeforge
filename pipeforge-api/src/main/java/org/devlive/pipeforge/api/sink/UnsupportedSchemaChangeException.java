/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.sink;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;

/** Thrown by writers that cannot apply a schema change. */
@Stable
public class UnsupportedSchemaChangeException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient SchemaChangeEvent event;

    public UnsupportedSchemaChangeException(SchemaChangeEvent event) {
        super("Unsupported schema change: " + event);
        this.event = event;
    }

    public SchemaChangeEvent event() {
        return event;
    }
}
