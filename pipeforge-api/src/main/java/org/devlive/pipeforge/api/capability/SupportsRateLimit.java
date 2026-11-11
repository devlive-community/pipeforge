/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Source capability: Limits its own rate instead of the engine. */
@Evolving
public interface SupportsRateLimit {
    void applyRateLimit(double rowsPerSecond);
}
