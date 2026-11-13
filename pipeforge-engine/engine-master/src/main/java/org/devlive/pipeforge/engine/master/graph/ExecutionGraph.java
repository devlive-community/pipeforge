/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A {@link JobGraph} expanded by parallelism: one task per vertex and subtask, the connections
 * between tasks, the regions and the slots.
 *
 * <ul>
 *   <li>A {@code forward} edge connects subtask i to subtask i; every other edge connects every
 *       task of the source to every task of the target.
 *   <li>A <b>region</b> is a set of tasks connected by edges; tasks that exchange no rows are in
 *       different regions, and a failure of one region does not touch the others.
 *   <li>Vertices share slots: subtask i of every vertex runs in slot i, so a job needs as many
 *       slots as its largest parallelism.
 * </ul>
 *
 * @param tasks every task, vertex by vertex in the order of the job graph, then by subtask
 * @param connections from task to task
 * @param regions the tasks of every region, ordered by their first task
 */
public record ExecutionGraph(JobGraph job, List<Task> tasks, List<Connection> connections, List<List<Task>> regions) {

    /**
     * One parallel instance of a vertex.
     *
     * @param slot the shared slot it runs in, the same as its subtask
     */
    public record Task(String vertex, int subtask, int slot) {
        @Override
        public String toString() {
            return vertex + "[" + subtask + "]";
        }
    }

    /** Rows from one task to another over an edge. */
    public record Connection(Task source, Task target, Partitioning partitioning) {}

    public ExecutionGraph {
        tasks = List.copyOf(tasks);
        connections = List.copyOf(connections);
        regions = regions.stream().map(List::copyOf).toList();
    }

    /** Expands {@code job}. */
    public static ExecutionGraph of(JobGraph job) {
        Map<String, List<Task>> byVertex = new LinkedHashMap<>();
        List<Task> tasks = new ArrayList<>();
        for (JobGraph.Vertex vertex : job.vertices()) {
            List<Task> instances = new ArrayList<>();
            for (int subtask = 0; subtask < vertex.parallelism(); subtask++) {
                instances.add(new Task(vertex.id(), subtask, subtask));
            }
            byVertex.put(vertex.id(), instances);
            tasks.addAll(instances);
        }

        List<Connection> connections = new ArrayList<>();
        for (JobGraph.Edge edge : job.edges()) {
            List<Task> sources = byVertex.get(edge.source());
            List<Task> targets = byVertex.get(edge.target());
            if (edge.partitioning().allToAll()) {
                for (Task source : sources) {
                    for (Task target : targets) {
                        connections.add(new Connection(source, target, edge.partitioning()));
                    }
                }
            } else {
                if (sources.size() != targets.size()) {
                    throw new IllegalArgumentException("A forward edge from " + edge.source() + " to " + edge.target()
                            + " needs the same parallelism on both sides, not " + sources.size() + " and "
                            + targets.size());
                }
                for (int i = 0; i < sources.size(); i++) {
                    connections.add(new Connection(sources.get(i), targets.get(i), edge.partitioning()));
                }
            }
        }
        return new ExecutionGraph(job, tasks, connections, regions(tasks, connections));
    }

    /** The connected parts of the task graph (union-find). */
    private static List<List<Task>> regions(List<Task> tasks, List<Connection> connections) {
        Map<Task, Task> parent = new HashMap<>();
        tasks.forEach(t -> parent.put(t, t));
        for (Connection connection : connections) {
            Task a = root(parent, connection.source());
            Task b = root(parent, connection.target());
            if (!a.equals(b)) {
                parent.put(b, a);
            }
        }
        // regions in the order of their first task
        Map<Task, List<Task>> byRoot = new LinkedHashMap<>();
        for (Task task : tasks) {
            byRoot.computeIfAbsent(root(parent, task), r -> new ArrayList<>()).add(task);
        }
        return new ArrayList<>(byRoot.values());
    }

    private static Task root(Map<Task, Task> parent, Task task) {
        Task current = task;
        while (!parent.get(current).equals(current)) {
            Task next = parent.get(current);
            parent.put(current, parent.get(next));
            current = next;
        }
        return current;
    }

    /** The slots the job needs: its largest parallelism. */
    public int slots() {
        return tasks.stream().mapToInt(Task::slot).max().orElse(-1) + 1;
    }

    /** The tasks of {@code vertex}, by subtask. */
    public List<Task> tasksOf(String vertex) {
        return tasks.stream()
                .filter(t -> t.vertex().equals(vertex))
                .sorted(Comparator.comparingInt(Task::subtask))
                .toList();
    }

    /** The region of {@code task}. */
    public List<Task> regionOf(Task task) {
        return regions.stream().filter(r -> r.contains(task)).findFirst().orElseThrow();
    }

    /** The tasks of every slot, as they share it. */
    public Map<Integer, List<Task>> slotSharing() {
        Map<Integer, List<Task>> slots = new TreeMap<>();
        tasks.forEach(
                t -> slots.computeIfAbsent(t.slot(), s -> new ArrayList<>()).add(t));
        return slots;
    }
}
