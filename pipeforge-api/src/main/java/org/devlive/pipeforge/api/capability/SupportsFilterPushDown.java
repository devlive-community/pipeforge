/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Source capability: Evaluates filters in the source system. */
@Evolving
public interface SupportsFilterPushDown {
    /**
     * Offers filters to the source.
     *
     * @return the filters the source could not apply; they stay in the job
     */
    java.util.List<org.devlive.pipeforge.api.expression.Predicate> applyFilters(
            java.util.List<org.devlive.pipeforge.api.expression.Predicate> filters);
}
