/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Sink capability: Prepares the target table and existing data before writing. */
@Evolving
public interface SupportsSaveMode {
    /** What to do with the target table before writing. */
    enum SchemaSaveMode {
        RECREATE_SCHEMA,
        CREATE_SCHEMA_WHEN_NOT_EXIST,
        ERROR_WHEN_SCHEMA_NOT_EXIST,
        IGNORE
    }

    /** What to do with data already in the target table. */
    enum DataSaveMode {
        DROP_DATA,
        APPEND_DATA,
        ERROR_WHEN_DATA_EXISTS
    }

    void handleSaveMode(SchemaSaveMode schemaSaveMode, DataSaveMode dataSaveMode) throws Exception;
}
