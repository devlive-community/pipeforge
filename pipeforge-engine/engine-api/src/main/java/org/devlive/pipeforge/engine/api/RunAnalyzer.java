/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Finds the bottleneck of a run from its metrics. The rules are checked in order and the first that
 * holds is the conclusion; the documentation lists them, with the advice each gives:
 *
 * <ol>
 *   <li>{@code THROTTLE}: tasks waited more than a fifth of their time for the rate limit of the
 *       job;
 *   <li>{@code TARGET}: readers waited more than half of their time for the write queue of the sink;
 *       or tasks waited more than half of their time for network buffers while a sink was busy more
 *       than half of its time; or a sink was busy more than half of its time, at least as busy as
 *       the sources, while write threads waited less than a fifth of theirs for rows;
 *   <li>{@code NETWORK}: rows crossed tasks and tasks waited more than half of their time for network
 *       buffers, while no sink was busy more than half of its time;
 *   <li>{@code SOURCE}: write threads waited more than half of their time for rows, or a source was
 *       busy more than half of its time while readers and tasks waited less than a fifth of theirs;
 *   <li>{@code NONE}: none of these.
 * </ol>
 *
 * <p>Shares are of the time tasks ran in the window; busy shares of nodes are those of the whole
 * run. Without task time in the window the conclusion is {@code UNKNOWN}.
 */
public final class RunAnalyzer {

    /** How far back a running job is judged. */
    public static final long WINDOW_MS = 10_000;

    /** A share above this is high. */
    public static final double HIGH = 0.5;

    /** A share below this is low. */
    public static final double LOW = 0.2;

    private RunAnalyzer() {}

    /**
     * Analyses a run.
     *
     * @param samples the metrics of the run as they were published, oldest first; the last one is
     *     the current one
     */
    public static RunAnalysis analyze(String job, JobStatus status, List<JobMetrics> samples) {
        JobMetrics last = samples.isEmpty() ? JobMetrics.EMPTY : samples.get(samples.size() - 1);
        JobMetrics base = JobMetrics.EMPTY;
        if (!status.isTerminal()) {
            // the newest sample at least a window old; a younger run is judged from its start
            for (JobMetrics sample : samples) {
                if (sample.elapsedMs() <= last.elapsedMs() - WINDOW_MS) {
                    base = sample;
                }
            }
        }
        Window window = new Window(base, last);
        return new RunAnalysis(
                RunAnalysis.SCHEMA_VERSION,
                job,
                status,
                base.elapsedMs(),
                last.elapsedMs(),
                last,
                window.rates(),
                bottleneck(window));
    }

    /** The difference between two samples. */
    private record Window(JobMetrics base, JobMetrics last) {

        long running() {
            return last.runningMs() - base.runningMs();
        }

        double share(long lastMs, long baseMs) {
            return running() <= 0 ? 0 : Math.min(1.0, Math.max(0, lastMs - baseMs) / (double) running());
        }

        double readerWait() {
            return share(last.readerWaitMs(), base.readerWaitMs());
        }

        double writerWait() {
            return share(last.writerWaitMs(), base.writerWaitMs());
        }

        double throttled() {
            return share(last.throttleWaitMs(), base.throttleWaitMs());
        }

        double backpressured() {
            return share(last.backpressuredMs(), base.backpressuredMs());
        }

        boolean exchanged() {
            return last.exchangedBytes() > base.exchangedBytes();
        }

        List<RunAnalysis.NodeRate> rates() {
            double seconds = (last.elapsedMs() - base.elapsedMs()) / 1000.0;
            Map<String, NodeMetrics> before = new HashMap<>();
            base.nodes().forEach(n -> before.put(n.node(), n));
            List<RunAnalysis.NodeRate> rates = new ArrayList<>();
            for (NodeMetrics node : last.nodes()) {
                NodeMetrics then = before.get(node.node());
                long in = node.rowsIn() - (then == null ? 0 : then.rowsIn());
                long out = node.rowsOut() - (then == null ? 0 : then.rowsOut());
                rates.add(new RunAnalysis.NodeRate(
                        node.node(), seconds > 0 ? in / seconds : 0, seconds > 0 ? out / seconds : 0));
            }
            return rates;
        }

        Optional<NodeMetrics> busiest(String kind) {
            return last.nodes().stream()
                    .filter(n -> n.kind().equals(kind))
                    .max(Comparator.comparingDouble(NodeMetrics::selfTimeRatio));
        }
    }

    private static Bottleneck bottleneck(Window window) {
        if (window.running() <= 0) {
            return new Bottleneck(Bottleneck.Kind.UNKNOWN, null, List.of(), List.of());
        }
        double readerWait = window.readerWait();
        double writerWait = window.writerWait();
        double backpressured = window.backpressured();
        NodeMetrics sink = window.busiest("sink").orElse(null);
        NodeMetrics source = window.busiest("source").orElse(null);
        double sinkBusy = sink == null ? -1 : sink.selfTimeRatio();
        double sourceBusy = source == null ? -1 : source.selfTimeRatio();
        int parallelism = window.last().parallelism();
        double throttled = window.throttled();
        if (throttled > LOW) {
            return new Bottleneck(
                    Bottleneck.Kind.THROTTLE,
                    source == null ? null : source.node(),
                    List.of(new Bottleneck.Evidence("throttleWaitShare", null, throttled)),
                    List.of(new Bottleneck.Advice(
                            "rate-limit",
                            "The rate limit of the job (env.limit) holds the readers back; raise it if the source"
                                    + " and the target can take more")));
        }

        List<Bottleneck.Evidence> shares = List.of(
                new Bottleneck.Evidence("readerWaitShare", null, readerWait),
                new Bottleneck.Evidence("backpressuredShare", null, backpressured),
                new Bottleneck.Evidence("writerWaitShare", null, writerWait));

        boolean busySink = sinkBusy >= HIGH && sinkBusy >= sourceBusy && writerWait < LOW;
        if (readerWait > HIGH || (backpressured > HIGH && sinkBusy >= HIGH) || busySink) {
            String node = sink == null ? null : sink.node();
            List<Bottleneck.Evidence> evidence;
            if (readerWait > HIGH) {
                evidence = with(shares.get(0), sink);
            } else if (backpressured > HIGH) {
                evidence = with(shares.get(1), sink);
            } else {
                // the sink shares a chain with its source: being busy is all it shows
                evidence = List.of(busy(sink), shares.get(2));
            }
            return new Bottleneck(
                    Bottleneck.Kind.TARGET,
                    node,
                    evidence,
                    List.of(
                            new Bottleneck.Advice(
                                    "sink-parallelism",
                                    "Raise the parallelism of " + name(node, "the sink") + " (now " + parallelism
                                            + ") or its batch size, if the target can take more writes"),
                            new Bottleneck.Advice(
                                    "check-target", "Check the write latency and the load of the target system")));
        }
        if (window.exchanged() && backpressured > HIGH && sinkBusy < HIGH) {
            NodeMetrics sender = window.last().nodes().stream()
                    .filter(n -> n.bytesOut() > 0)
                    .max(Comparator.comparingLong(NodeMetrics::bytesOut))
                    .orElse(null);
            List<Bottleneck.Evidence> evidence = new ArrayList<>(List.of(shares.get(1)));
            if (sink != null) {
                evidence.add(busy(sink));
            }
            evidence.add(new Bottleneck.Evidence(
                    "exchangedBytes",
                    null,
                    window.last().exchangedBytes() - window.base().exchangedBytes()));
            return new Bottleneck(
                    Bottleneck.Kind.NETWORK,
                    sender == null ? null : sender.node(),
                    evidence,
                    List.of(
                            new Bottleneck.Advice(
                                    "network-buffers",
                                    "Raise network.buffers-per-channel or network.floating-buffers-per-gate"),
                            new Bottleneck.Advice(
                                    "check-network", "Check the network between the workers and their load")));
        }
        if (writerWait > HIGH || (readerWait < LOW && backpressured < LOW && sourceBusy >= HIGH)) {
            String node = source == null ? null : source.node();
            return new Bottleneck(
                    Bottleneck.Kind.SOURCE,
                    node,
                    with(writerWait > HIGH ? shares.get(2) : shares.get(0), source),
                    List.of(
                            new Bottleneck.Advice(
                                    "source-parallelism",
                                    "Raise the parallelism of " + name(node, "the source") + " (now " + parallelism
                                            + ") or let it read more splits"),
                            new Bottleneck.Advice(
                                    "check-source", "Check the query latency and the load of the source system")));
        }
        return new Bottleneck(Bottleneck.Kind.NONE, null, shares, List.of());
    }

    private static List<Bottleneck.Evidence> with(Bottleneck.Evidence share, @Nullable NodeMetrics node) {
        if (node == null || node.selfTimeRatio() < 0) {
            return List.of(share);
        }
        return List.of(share, busy(node));
    }

    private static Bottleneck.Evidence busy(NodeMetrics node) {
        return new Bottleneck.Evidence("selfTimeRatio", node.node(), node.selfTimeRatio());
    }

    private static String name(@Nullable String node, String otherwise) {
        return node == null ? otherwise : node;
    }
}
