/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Sink capability: Handles UPDATE and DELETE rows by primary key. */
@Evolving
public interface SupportsUpsert {
    /** The key columns used for upserts of a table, or empty to use its primary key. */
    default java.util.List<String> upsertKeys(org.devlive.pipeforge.api.row.TableId table) {
        return java.util.List.of();
    }
}
