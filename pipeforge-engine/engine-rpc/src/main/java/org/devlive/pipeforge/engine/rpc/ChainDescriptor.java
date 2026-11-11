/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.List;
import java.util.Objects;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.PlannedNode;

/**
 * The operator chain a task runs: a source, its transforms in order and a sink, merged into one
 * vertex because they are connected one to one with the same parallelism.
 *
 * <p>In process the planned nodes are passed as they are; across processes the chain is sent as a
 * versioned binary descriptor and the worker resolves the components from its plugin cache.
 *
 * @param vertexId the vertex id, the id of the first node
 * @param job job-level information for the plugins
 * @param nodes the nodes of the chain, from the source to the sink
 */
public record ChainDescriptor(String vertexId, JobContext job, List<PlannedNode> nodes) {

    public ChainDescriptor {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(job, "job");
        nodes = List.copyOf(nodes);
        if (nodes.size() < 2) {
            throw new IllegalArgumentException("A chain needs at least a source and a sink");
        }
        if (nodes.get(0).kind() != NodeKind.SOURCE) {
            throw new IllegalArgumentException(
                    "A chain starts with a source, not " + nodes.get(0).id());
        }
        PlannedNode last = nodes.get(nodes.size() - 1);
        if (last.kind() != NodeKind.SINK) {
            throw new IllegalArgumentException("A chain ends with a sink, not " + last.id());
        }
        for (PlannedNode node : nodes.subList(1, nodes.size() - 1)) {
            if (node.kind() != NodeKind.TRANSFORM) {
                throw new IllegalArgumentException("Only transforms can be in the middle of a chain, not " + node.id());
            }
        }
    }

    public PlannedNode source() {
        return nodes.get(0);
    }

    public List<PlannedNode> transforms() {
        return nodes.subList(1, nodes.size() - 1);
    }

    public PlannedNode sink() {
        return nodes.get(nodes.size() - 1);
    }
}
