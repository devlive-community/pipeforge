/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Source capability: Estimates the total row count for progress and ETA. */
@Evolving
public interface SupportsRowCount {
    /** Estimated rows, or -1 when unknown. */
    long estimatedRowCount();
}
