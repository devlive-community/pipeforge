/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Source capability: Reads only the requested columns. */
@Evolving
public interface SupportsProjectionPushDown {
    /** Restricts the produced columns of a table to {@code columns}, in this order. */
    void applyProjection(org.devlive.pipeforge.api.row.TableId table, java.util.List<String> columns);
}
