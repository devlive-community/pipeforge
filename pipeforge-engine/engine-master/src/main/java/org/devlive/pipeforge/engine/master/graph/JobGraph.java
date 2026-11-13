/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;

/**
 * A plan with its operators chained into vertices (native engine design, section 4): a node joins
 * the vertex of its upstream node when it is the only consumer of that node, reads from nothing
 * else, has the same parallelism and receives its rows by {@link Partitioning.Kind#FORWARD}. The
 * edges between vertices carry the partitioning that the rules choose:
 *
 * <ul>
 *   <li>{@code hash} into a sink with a parallelism above 1 whose input may contain updates or
 *       deletes, so that the changes of one key reach one writer in order;
 *   <li>{@code rebalance} where the parallelism changes;
 *   <li>{@code forward} otherwise.
 * </ul>
 *
 * @param vertices in topological order of their first node
 * @param edges between vertices
 */
public record JobGraph(String job, List<Vertex> vertices, List<Edge> edges) {

    /**
     * A chain of nodes that runs in one task per parallel instance.
     *
     * @param id the ID of its first node
     */
    public record Vertex(String id, List<PlannedNode> nodes, int parallelism) {
        public Vertex {
            nodes = List.copyOf(nodes);
        }

        public PlannedNode head() {
            return nodes.get(0);
        }

        public PlannedNode tail() {
            return nodes.get(nodes.size() - 1);
        }
    }

    /**
     * Rows from the last node of {@code source} to the first node of {@code target}.
     *
     * @param input the position of the source among the inputs of the target's first node
     */
    public record Edge(String source, String target, Partitioning partitioning, int input) {}

    public JobGraph {
        vertices = List.copyOf(vertices);
        edges = List.copyOf(edges);
    }

    public Optional<Vertex> vertex(String id) {
        return vertices.stream().filter(v -> v.id().equals(id)).findFirst();
    }

    /** The edges into {@code vertex}, by input position. */
    public List<Edge> inputs(String vertex) {
        return edges.stream()
                .filter(e -> e.target().equals(vertex))
                .sorted(java.util.Comparator.comparingInt(Edge::input))
                .toList();
    }

    /** The edges out of {@code vertex}. */
    public List<Edge> outputs(String vertex) {
        return edges.stream().filter(e -> e.source().equals(vertex)).toList();
    }

    /** The graph of {@code plan}, with nodes that may emit updates or deletes found by {@link #mayChange}. */
    public static JobGraph of(JobPlan plan) {
        return of(plan, mayChange(plan));
    }

    /**
     * The graph of {@code plan}.
     *
     * @param changes whether a node's output may contain updates or deletes
     */
    public static JobGraph of(JobPlan plan, Predicate<PlannedNode> changes) {
        Map<String, PlannedNode> nodes = new LinkedHashMap<>();
        plan.nodes().forEach(n -> nodes.put(n.id(), n));

        // the partitioning of every node-to-node link
        Map<String, Partitioning> links = new HashMap<>();
        for (PlannedNode node : plan.nodes()) {
            for (String input : node.inputs()) {
                links.put(input + "->" + node.id(), choose(nodes.get(input), node, changes));
            }
        }

        // chains: follow single forward links from every node that starts a vertex
        Map<String, String> vertexOf = new HashMap<>();
        List<List<PlannedNode>> chains = new ArrayList<>();
        for (PlannedNode node : plan.nodes()) {
            if (vertexOf.containsKey(node.id()) || chainsInto(plan, nodes, links, node)) {
                continue;
            }
            List<PlannedNode> chain = new ArrayList<>(List.of(node));
            PlannedNode current = node;
            while (true) {
                List<String> downstream = plan.downstream(current.id());
                if (downstream.size() != 1) {
                    break;
                }
                PlannedNode next = nodes.get(downstream.get(0));
                if (!chainsInto(plan, nodes, links, next)) {
                    break;
                }
                chain.add(next);
                current = next;
            }
            chain.forEach(n -> vertexOf.put(n.id(), node.id()));
            chains.add(chain);
        }

        List<Vertex> vertices = new ArrayList<>();
        for (List<PlannedNode> chain : chains) {
            vertices.add(new Vertex(chain.get(0).id(), chain, chain.get(0).parallelism()));
        }
        List<Edge> edges = new ArrayList<>();
        for (Vertex vertex : vertices) {
            PlannedNode head = vertex.head();
            for (int i = 0; i < head.inputs().size(); i++) {
                String input = head.inputs().get(i);
                edges.add(new Edge(vertexOf.get(input), vertex.id(), links.get(input + "->" + head.id()), i));
            }
        }
        return new JobGraph(plan.name(), vertices, edges);
    }

    /** Whether {@code node} joins the vertex of its only input. */
    private static boolean chainsInto(
            JobPlan plan, Map<String, PlannedNode> nodes, Map<String, Partitioning> links, PlannedNode node) {
        if (node.inputs().size() != 1) {
            return false;
        }
        String input = node.inputs().get(0);
        return plan.downstream(input).size() == 1
                && nodes.get(input).parallelism() == node.parallelism()
                && links.get(input + "->" + node.id()).kind() == Partitioning.Kind.FORWARD;
    }

    /** The partitioning of the link from {@code upstream} to {@code downstream}. */
    static Partitioning choose(PlannedNode upstream, PlannedNode downstream, Predicate<PlannedNode> changes) {
        if (downstream.kind() == NodeKind.SINK && downstream.parallelism() > 1 && changes.test(upstream)) {
            return Partitioning.hash(keys(downstream.inputTables()));
        }
        return upstream.parallelism() == downstream.parallelism() ? Partitioning.FORWARD : Partitioning.REBALANCE;
    }

    /** The primary key of the only table, or none to hash by table and primary key. */
    private static List<String> keys(List<CatalogTable> tables) {
        return tables.size() == 1 ? tables.get(0).primaryKey() : List.of();
    }

    /**
     * Which nodes may emit updates or deletes, until the planner derives change modes (stateful
     * processing design): an unbounded source may, a bounded one does not, and other nodes may when
     * one of their inputs may. A {@code hash} that is not needed costs throughput, a missing one
     * would reorder the changes of a key, so this errs towards {@code hash}.
     */
    public static Predicate<PlannedNode> mayChange(JobPlan plan) {
        Map<String, PlannedNode> nodes = new HashMap<>();
        plan.nodes().forEach(n -> nodes.put(n.id(), n));
        Set<String> changing = new HashSet<>();
        for (PlannedNode node : plan.nodes()) {
            boolean value = node.kind() == NodeKind.SOURCE
                    ? node.boundedness() != Boundedness.BOUNDED
                    : node.inputs().stream().anyMatch(changing::contains);
            if (value) {
                changing.add(node.id());
            }
        }
        return node -> changing.contains(node.id());
    }
}
