/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.api.factory.EngineKind;
import org.devlive.pipeforge.core.job.Delivery;
import org.devlive.pipeforge.core.job.JobMode;
import org.devlive.pipeforge.core.job.NodeKind;
import org.jspecify.annotations.Nullable;

/**
 * The validated logical plan of a job: settings, nodes in topological order with their options
 * and schemas, and warnings.
 *
 * @param name the job name
 * @param description the job description, or {@code null}
 * @param engine the engine that runs the job
 * @param mode batch or streaming
 * @param modeDerived whether the mode was derived from the sources rather than written
 * @param delivery the required delivery guarantee
 * @param parallelism the default parallelism of the nodes
 * @param timeZone the job time zone
 * @param variables the resolved job variables
 * @param nodes the nodes in topological order (ties in the order written)
 * @param warnings problems that do not stop the job, in a stable order
 */
public record JobPlan(
        String name,
        @Nullable String description,
        EngineKind engine,
        JobMode mode,
        boolean modeDerived,
        Delivery delivery,
        int parallelism,
        ZoneId timeZone,
        Map<String, String> variables,
        List<PlannedNode> nodes,
        List<String> warnings) {

    public JobPlan {
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        nodes = List.copyOf(nodes);
        warnings = List.copyOf(warnings);
    }

    public Optional<PlannedNode> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    public List<PlannedNode> nodes(NodeKind kind) {
        return nodes.stream().filter(n -> n.kind() == kind).toList();
    }

    /** The ids of the nodes that read from {@code id}. */
    public List<String> downstream(String id) {
        return nodes.stream()
                .filter(n -> n.inputs().contains(id))
                .map(PlannedNode::id)
                .toList();
    }
}
