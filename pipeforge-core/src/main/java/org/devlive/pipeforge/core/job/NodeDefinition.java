/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One node of a job as written, after variables are resolved.
 *
 * @param id the stable id; checkpoint state is matched by it
 * @param kind source, transform or sink
 * @param type the component identifier, e.g. {@code fake}
 * @param plugin the plugin providing the component, {@code id} or {@code id@version}; {@code null}
 *     lets the planner pick the only plugin that provides it
 * @param inputs ids of the upstream nodes; empty for sources
 * @param inputsImplied whether {@code inputs} was omitted and taken from the preceding node
 * @param parallelism the parallelism of the node, or {@code null} for the job's
 * @param options the remaining fields: the plugin options, nested mappings flattened to dotted keys
 * @param location where the node is written
 */
public record NodeDefinition(
        String id,
        NodeKind kind,
        String type,
        @Nullable String plugin,
        List<String> inputs,
        boolean inputsImplied,
        @Nullable Integer parallelism,
        Map<String, Object> options,
        Location location) {

    public NodeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        inputs = List.copyOf(inputs);
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        Objects.requireNonNull(location, "location");
    }
}
