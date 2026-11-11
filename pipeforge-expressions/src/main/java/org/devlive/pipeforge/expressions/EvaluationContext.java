/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import java.time.ZoneId;
import java.util.Objects;

/**
 * What expressions may depend on besides the row: the job time zone, used for local dates and
 * times and for conversions to and from {@code TIMESTAMP_LTZ}.
 */
public record EvaluationContext(ZoneId timeZone) {

    public EvaluationContext {
        Objects.requireNonNull(timeZone, "timeZone");
    }

    public static EvaluationContext utc() {
        return new EvaluationContext(ZoneId.of("UTC"));
    }
}
