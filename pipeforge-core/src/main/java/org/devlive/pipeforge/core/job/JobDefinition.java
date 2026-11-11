/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.jspecify.annotations.Nullable;

/**
 * A job as written, after variables are resolved and the structure is checked; the input of the
 * planner. Create it with {@link JobParser}.
 *
 * @param version the DSL version, currently always 1
 * @param name the job name
 * @param description free text, or {@code null}
 * @param timeZone the job time zone: env.timezone or the default of the planning context
 * @param variables the job variables with their resolved values
 * @param env the env section flattened to dotted keys, without {@code variables}
 * @param nodes all nodes: sources, then transforms, then sinks, each in the order written
 */
public record JobDefinition(
        int version,
        String name,
        @Nullable String description,
        ZoneId timeZone,
        Map<String, String> variables,
        ReadonlyConfig env,
        List<NodeDefinition> nodes) {

    public JobDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(timeZone, "timeZone");
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        Objects.requireNonNull(env, "env");
        nodes = List.copyOf(nodes);
    }

    public List<NodeDefinition> nodes(NodeKind kind) {
        return nodes.stream().filter(n -> n.kind() == kind).toList();
    }

    public Optional<NodeDefinition> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }
}
