/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The structured error object returned to the server, the UI and the CLI. {@code message} and
 * {@code hint} are rendered in the requested language; clients may re-render them from {@code
 * code} and {@code params}.
 */
public record ErrorInfo(
        String code,
        String name,
        Map<String, Object> params,
        Map<String, Object> scope,
        ErrorKind kind,
        String message,
        String hint,
        List<String> causeChain) {

    public ErrorInfo {
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        scope = Collections.unmodifiableMap(new LinkedHashMap<>(scope));
        causeChain = List.copyOf(causeChain);
    }
}
