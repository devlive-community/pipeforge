/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Source or sink capability: Handles many tables in one instance (whole-database sync). */
@Evolving
public interface SupportsMultiTable {
    /** Maximum tables per instance, or -1 for no limit. */
    default int maxTables() {
        return -1;
    }
}
