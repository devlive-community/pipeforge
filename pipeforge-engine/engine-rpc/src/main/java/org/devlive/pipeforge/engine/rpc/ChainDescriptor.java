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
 * The operator chain a task runs: nodes merged into one vertex because they are connected one to
 * one with the same parallelism. A chain starts with a source or reads its first node's input from
 * the network, and ends with a sink or writes its last node's output to the network.
 *
 * <p>In process the planned nodes are passed as they are; across processes the chain is sent as a
 * versioned binary descriptor and the worker resolves the components from its plugin cache.
 *
 * @param vertexId the vertex id, the id of the first node
 * @param job job-level information for the plugins
 * @param nodes the nodes of the chain, in order
 * @param outputs where the last node's rows go when it is not a sink, one partition per edge
 */
public record ChainDescriptor(String vertexId, JobContext job, List<PlannedNode> nodes, List<Output> outputs) {

    /**
     * An output edge of the chain.
     *
     * @param index the number of the edge, as the consumers name it
     * @param targetVertex the vertex that reads it
     * @param partitioning how rows are spread over the subpartitions
     * @param subpartitions one per subtask of the target
     */
    public record Output(int index, String targetVertex, Partitioning partitioning, int subpartitions) {
        public Output {
            if (index < 0 || subpartitions < 1) {
                throw new IllegalArgumentException("output " + index + " with " + subpartitions + " subpartitions");
            }
        }
    }

    public ChainDescriptor {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(job, "job");
        nodes = List.copyOf(nodes);
        outputs = List.copyOf(outputs);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("A chain needs at least one node");
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeKind kind = nodes.get(i).kind();
            if (kind == NodeKind.SOURCE && i > 0) {
                throw new IllegalArgumentException("A source can only start a chain, not follow "
                        + nodes.get(i - 1).id());
            }
            if (kind == NodeKind.SINK && i < nodes.size() - 1) {
                throw new IllegalArgumentException("A sink can only end a chain, not precede "
                        + nodes.get(i + 1).id());
            }
        }
        PlannedNode last = nodes.get(nodes.size() - 1);
        if (last.kind() == NodeKind.SINK && !outputs.isEmpty()) {
            throw new IllegalArgumentException("A chain that ends with a sink has no outputs");
        }
        if (last.kind() != NodeKind.SINK && outputs.isEmpty()) {
            throw new IllegalArgumentException(
                    "A chain must end with a sink or write to outputs, not end at " + last.id());
        }
    }

    /** A chain from a source to a sink. */
    public ChainDescriptor(String vertexId, JobContext job, List<PlannedNode> nodes) {
        this(vertexId, job, nodes, List.of());
    }

    public PlannedNode head() {
        return nodes.get(0);
    }

    public PlannedNode tail() {
        return nodes.get(nodes.size() - 1);
    }

    /** Whether the chain starts with a source; otherwise its first node reads from the network. */
    public boolean hasSource() {
        return head().kind() == NodeKind.SOURCE;
    }

    /** Whether the chain ends with a sink; otherwise its last node writes to {@link #outputs}. */
    public boolean hasSink() {
        return tail().kind() == NodeKind.SINK;
    }

    public PlannedNode source() {
        if (!hasSource()) {
            throw new IllegalStateException("chain " + vertexId + " reads from the network, not from a source");
        }
        return head();
    }

    /** The transforms of the chain, in order. */
    public List<PlannedNode> transforms() {
        return nodes.stream().filter(n -> n.kind() == NodeKind.TRANSFORM).toList();
    }

    public PlannedNode sink() {
        if (!hasSink()) {
            throw new IllegalStateException("chain " + vertexId + " writes to the network, not to a sink");
        }
        return tail();
    }
}
