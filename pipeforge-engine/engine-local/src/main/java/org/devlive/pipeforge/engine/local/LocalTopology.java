/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;

/**
 * Turns a plan into the one operator chain the local engine runs. Without data exchange between
 * tasks, the job must be a straight line: one source, its transforms, one sink, each node reading
 * from the one before and all with the same parallelism.
 */
final class LocalTopology {

    private LocalTopology() {}

    /** The nodes of the chain from the source to the sink, or {@code PF-PLAN-0012}. */
    static List<PlannedNode> chain(JobPlan plan) {
        List<PlannedNode> sources = plan.nodes(NodeKind.SOURCE);
        if (sources.size() != 1) {
            String node = sources.isEmpty()
                    ? plan.nodes().get(0).id()
                    : sources.get(1).id();
            throw unsupported(node, "the job has " + sources.size() + " sources; one source per job is supported");
        }
        List<PlannedNode> chain = new ArrayList<>();
        PlannedNode current = sources.get(0);
        while (true) {
            chain.add(current);
            if (current.parallelism() != sources.get(0).parallelism()) {
                throw unsupported(
                        current.id(),
                        "its parallelism " + current.parallelism() + " differs from the source's "
                                + sources.get(0).parallelism()
                                + "; redistributing rows between tasks is not supported");
            }
            List<String> downstream = plan.downstream(current.id());
            if (current.kind() == NodeKind.SINK) {
                break;
            }
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
        if (chain.size() != plan.nodes().size()) {
            PlannedNode extra = plan.nodes().stream()
                    .filter(n -> !chain.contains(n))
                    .findFirst()
                    .orElseThrow();
            throw unsupported(extra.id(), "it is not on the path from the source to the sink");
        }
        return chain;
    }

    private static PipeForgeException unsupported(String node, String reason) {
        return PipeForgeException.of(ErrorCode.TOPOLOGY_UNSUPPORTED)
                .param("node", node)
                .param("reason", reason)
                .scope(ErrorScope.node(node))
                .build();
    }
}
