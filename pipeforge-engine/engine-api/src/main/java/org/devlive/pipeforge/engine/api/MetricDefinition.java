/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.Map;
import java.util.Objects;

/**
 * A metric of the catalog.
 *
 * @param name the name in lower camel case, e.g. {@code rowsRead}
 * @param scope where it is reported: {@code job} or {@code node}
 * @param type {@code counter}, {@code gauge}, {@code meter} or {@code timer}
 * @param unit {@code rows}, {@code ms}, {@code rows/s}, {@code ratio} or {@code tasks}
 * @param description what it means, by language ({@code en-US}, {@code zh-CN})
 */
public record MetricDefinition(String name, String scope, String type, String unit, Map<String, String> description) {

    public MetricDefinition {
        Objects.requireNonNull(name, "name");
        description = Map.copyOf(description);
    }
}
