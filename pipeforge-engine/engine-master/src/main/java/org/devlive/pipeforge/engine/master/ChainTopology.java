/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.master.graph.JobGraph;

/**
 * Checks that the engine can run a plan and turns it into its job graph. This version runs a
 * pipeline: one source, its transforms and one sink, each node reading from the one before. Nodes
 * may have different parallelism; where it changes, or where a parallel sink needs the changes of
 * a key in order, the rows are exchanged between tasks.
 */
public final class ChainTopology {

    private ChainTopology() {}

    /** The job graph of {@code plan}, or {@code PF-PLAN-0012} for a shape this version cannot run. */
    public static JobGraph graph(JobPlan plan) {
        List<PlannedNode> sources = plan.nodes(NodeKind.SOURCE);
        if (sources.size() != 1) {
            String node = sources.isEmpty()
                    ? plan.nodes().get(0).id()
                    : sources.get(1).id();
            throw unsupported(node, "the job has " + sources.size() + " sources; one source per job is supported");
        }
        List<PlannedNode> path = new ArrayList<>();
        PlannedNode current = sources.get(0);
        while (true) {
            path.add(current);
            if (current.kind() == NodeKind.SINK) {
                break;
            }
            List<String> downstream = plan.downstream(current.id());
            if (downstream.size() != 1) {
                throw unsupported(
                        current.id(),
                        "its output goes to " + downstream.size() + " nodes; one downstream node is supported");
            }
            current = plan.node(downstream.get(0)).orElseThrow();
            if (current.inputs().size() != 1) {
                throw unsupported(
                        current.id(), "it reads from " + current.inputs().size() + " nodes; one input is supported");
            }
        }
        if (path.size() != plan.nodes().size()) {
            PlannedNode extra = plan.nodes().stream()
                    .filter(n -> !path.contains(n))
                    .findFirst()
                    .orElseThrow();
            throw unsupported(extra.id(), "it is not on the path from the source to the sink");
        }
        JobGraph graph = JobGraph.of(plan);
        for (JobGraph.Edge edge : graph.edges()) {
            PlannedNode upstream = graph.vertex(edge.source()).orElseThrow().tail();
            PlannedNode downstream = graph.vertex(edge.target()).orElseThrow().head();
            if (!ids(upstream.outputTables()).equals(ids(downstream.inputTables()))) {
                throw unsupported(
                        downstream.id(),
                        "it reads the tables " + ids(downstream.inputTables()) + " where " + upstream.id()
                                + " writes " + ids(upstream.outputTables())
                                + "; both ends of a data exchange need the same tables");
            }
        }
        return graph;
    }

    private static List<String> ids(List<CatalogTable> tables) {
        return tables.stream().map(t -> t.id().toQualifiedName()).toList();
    }

    private static PipeForgeException unsupported(String node, String reason) {
        return PipeForgeException.of(ErrorCode.TOPOLOGY_UNSUPPORTED)
                .param("node", node)
                .param("reason", reason)
                .scope(ErrorScope.node(node))
                .build();
    }
}
