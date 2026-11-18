/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An error as the server describes it: a registered code, a message, and what to do about it.
 *
 * @param code the registered error code
 * @param name its symbolic name
 * @param message the message
 * @param hint what to do about it
 * @param kind its kind
 * @param params the values in the message
 * @param scope where it happened: node, table, subtask…
 */
public record ApiError(
        String code,
        @Nullable String name,
        String message,
        @Nullable String hint,
        @Nullable String kind,
        Map<String, Object> params,
        Map<String, Object> scope) {

    public ApiError {
        params = params == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(params));
        scope = scope == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(scope));
    }
}
