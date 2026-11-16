/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.api.NodeMetrics;
import org.devlive.pipeforge.engine.api.Rate;
import org.devlive.pipeforge.engine.master.graph.JobGraph;
import org.devlive.pipeforge.engine.rpc.OperatorSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;

/**
 * Sums the metrics the tasks report into job and node metrics, and derives the rates from samples
 * taken once per second. Used only on the job master thread.
 */
final class MetricsAggregator {

    /** How the job stands when the metrics are computed. */
    enum Phase {
        RUNNING,
        SUCCEEDED,
        /** Failed or cancelled: rows that did not reach the target count as failed. */
        STOPPED
    }

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);

    private record Sample(long nanos, long read, long written) {}

    /** The nodes of every vertex in order: a pipeline from the source to the sink. */
    private final List<PlannedNode> nodes = new ArrayList<>();
    /** Where the nodes of each vertex start in {@link #nodes}. */
    private final Map<String, Integer> offsets = new HashMap<>();

    private final int parallelism;
    private final long started;
    private final Map<String, TaskMetricsSnapshot> tasks = new HashMap<>();
    private final Deque<Sample> window = new ArrayDeque<>();
    private double peakRead;
    private double peakWritten;
    private boolean twoPhase;
    private boolean committed;

    MetricsAggregator(JobGraph graph, long startedNanos) {
        int tasksTotal = 0;
        for (JobGraph.Vertex vertex : graph.vertices()) {
            offsets.put(vertex.id(), nodes.size());
            nodes.addAll(vertex.nodes());
            tasksTotal += vertex.parallelism();
        }
        this.parallelism = tasksTotal;
        this.started = startedNanos;
    }

    void update(String vertex, int subtask, TaskMetricsSnapshot metrics) {
        tasks.put(vertex + "/" + subtask, metrics);
    }

    /** The sink prepared committables: its rows count as written once they are committed. */
    void twoPhase() {
        twoPhase = true;
    }

    void committed() {
        committed = true;
    }

    /** Takes a rate sample and returns the metrics as of {@code now}. */
    JobMetrics sample(long now, int tasksRunning, Phase phase) {
        long[] in = new long[nodes.size()];
        long[] out = new long[nodes.size()];
        long[] filtered = new long[nodes.size()];
        long[] expanded = new long[nodes.size()];
        long[] self = new long[nodes.size()];
        long running = 0;
        long readerWait = 0;
        long writerWait = 0;
        for (Map.Entry<String, TaskMetricsSnapshot> entry : tasks.entrySet()) {
            TaskMetricsSnapshot task = entry.getValue();
            String vertex = entry.getKey().substring(0, entry.getKey().lastIndexOf('/'));
            int offset = offsets.getOrDefault(vertex, 0);
            running += task.runningNanos();
            readerWait += task.readerWaitNanos();
            writerWait += task.writerWaitNanos();
            List<OperatorSnapshot> operators = task.operators();
            for (int i = 0; i < operators.size() && offset + i < nodes.size(); i++) {
                OperatorSnapshot operator = operators.get(i);
                int node = offset + i;
                in[node] += operator.rowsIn();
                out[node] += operator.rowsOut();
                filtered[node] += operator.rowsFiltered();
                expanded[node] += operator.rowsExpanded();
                self[node] += operator.selfTimeNanos();
            }
        }
        int sink = nodes.size() - 1;
        long read = out[0];
        long accepted = out[sink];
        long written = twoPhase && !committed && phase != Phase.RUNNING ? 0 : accepted;
        long totalFiltered = 0;
        long totalExpanded = 0;
        for (int i = 1; i < sink; i++) {
            totalFiltered += filtered[i];
            totalExpanded += expanded[i];
        }
        long inFlight = 0;
        long failed = 0;
        switch (phase) {
                // Everything read and not yet accepted by the sink, wherever it is: the identity holds for
                // a snapshot taken while the task moves rows (see OperatorChain.snapshot).
            case RUNNING -> inFlight = read + totalExpanded - totalFiltered - accepted;
            case SUCCEEDED -> inFlight = in[sink] - written;
            case STOPPED -> failed = Math.max(0, read + totalExpanded - totalFiltered - written);
        }

        List<NodeMetrics> nodeMetrics = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            PlannedNode node = nodes.get(i);
            double ratio = running == 0 ? -1 : Math.min(1.0, (double) self[i] / running);
            long nodeOut = i == sink ? written : out[i];
            nodeMetrics.add(
                    new NodeMetrics(node.id(), node.kind().id(), in[i], nodeOut, filtered[i], expanded[i], ratio));
        }

        window.addLast(new Sample(now, read, written));
        Sample previous = window.size() > 1 ? secondToLast() : null;
        if (previous != null && now > previous.nanos()) {
            double seconds = (now - previous.nanos()) / 1e9;
            if (seconds >= 0.5) {
                peakRead = Math.max(peakRead, (read - previous.read()) / seconds);
                peakWritten = Math.max(peakWritten, (written - previous.written()) / seconds);
            }
        }
        while (window.size() > 2 && now - window.peekFirst().nanos() > WINDOW_NANOS) {
            window.removeFirst();
        }
        long elapsed = now - started;
        Sample first = window.peekFirst();
        double span = (now - first.nanos()) / 1e9;
        double currentRead = span > 0 ? (read - first.read()) / span : 0;
        double currentWritten = span > 0 ? (written - first.written()) / span : 0;
        double seconds = elapsed / 1e9;
        double averageRead = seconds > 0 ? read / seconds : 0;
        double averageWritten = seconds > 0 ? written / seconds : 0;
        // a job shorter than one sample still has a peak: its average
        peakRead = Math.max(peakRead, Math.max(currentRead, averageRead));
        peakWritten = Math.max(peakWritten, Math.max(currentWritten, averageWritten));
        if (phase != Phase.RUNNING) {
            currentRead = 0;
            currentWritten = 0;
        }

        return new JobMetrics(
                TimeUnit.NANOSECONDS.toMillis(elapsed),
                read,
                written,
                totalFiltered,
                totalExpanded,
                failed,
                inFlight,
                new Rate(currentRead, averageRead, peakRead),
                new Rate(currentWritten, averageWritten, peakWritten),
                TimeUnit.NANOSECONDS.toMillis(readerWait),
                TimeUnit.NANOSECONDS.toMillis(writerWait),
                parallelism,
                tasksRunning,
                nodeMetrics);
    }

    private Sample secondToLast() {
        Sample last = window.removeLast();
        Sample previous = window.peekLast();
        window.addLast(last);
        return previous;
    }
}
