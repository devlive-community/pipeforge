/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.option;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;

/** One problem found by {@link OptionRule#validate}: an error code with its parameters. */
@Stable
public record OptionViolation(ErrorCode code, Map<String, Object> params) {

    public OptionViolation {
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** The violation as an exception, scoped to {@code node}. */
    public PipeForgeException toException() {
        PipeForgeException.Builder builder = PipeForgeException.of(code);
        params.forEach(builder::param);
        Object node = params.get("node");
        if (node != null) {
            builder.scope(ErrorScope.node(String.valueOf(node)));
        }
        return builder.build();
    }
}
