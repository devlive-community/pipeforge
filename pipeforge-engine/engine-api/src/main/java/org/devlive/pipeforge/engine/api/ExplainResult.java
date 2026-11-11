/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.Objects;
import org.devlive.pipeforge.core.explain.ExplainDocument;

/**
 * The plan of a job as an engine runs it.
 *
 * @param plan the structured plan, as in {@code explain --format json}
 * @param text the plan as aligned text, as in {@code explain}
 */
public record ExplainResult(ExplainDocument plan, String text) {

    public ExplainResult {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(text, "text");
    }
}
