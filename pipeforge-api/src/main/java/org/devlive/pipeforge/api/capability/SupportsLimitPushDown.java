/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Source capability: Stops after a number of rows, for previews and sampling. */
@Evolving
public interface SupportsLimitPushDown {
    void applyLimit(long limit);
}
