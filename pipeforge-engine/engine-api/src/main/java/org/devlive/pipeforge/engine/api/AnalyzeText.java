/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The run analysis of a job as aligned text, the form {@code explain --analyze} prints:
 *
 * <pre>
 * Job orders-copy  SUCCESS 12.4s  parallelism 4
 * Rows   read 1,000,000  written 1,000,000  filtered 0  in-flight 0  failed 0   reconcile OK
 * Rate   read 80,645 r/s (avg 80,645, peak 91,200)   write 80,645 r/s (avg 80,645, peak 91,000)
 * Wait   reader 1.2s (target slow)  writer 0.3s (source slow)  backpressured 0ms (downstream slow)
 *
 * Node   kind       in rows    out rows   sel.   in r/s  self%
 * src    source     1,000,000  1,000,000  -      -       21%
 * map    transform  1,000,000  1,000,000  1.00   80,645  4%
 * out    sink       1,000,000  1,000,000  -      80,645  68%
 *
 * Bottleneck  TARGET at out: readers waited 62% of their time for the write queue; out busy 68%
 *   → Raise the parallelism of out (now 4) or its batch size, if the target can take more writes
 *   → Check the write latency and the load of the target system
 * </pre>
 *
 * <p>A running job shows the rates and the bottleneck of the last 10 seconds, a job that ended
 * those of the whole run.
 */
public final class AnalyzeText {

    private AnalyzeText() {}

    /** The analysis of a run whose only sample is {@code metrics}. */
    public static String format(String job, JobStatus status, JobMetrics metrics) {
        return format(RunAnalyzer.analyze(job, status, List.of(metrics)));
    }

    public static String format(RunAnalysis analysis) {
        String job = analysis.job();
        JobStatus status = analysis.status();
        JobMetrics metrics = analysis.metrics();
        StringBuilder out = new StringBuilder();
        out.append("Job ")
                .append(job)
                .append("  ")
                .append(status)
                .append(' ')
                .append(duration(metrics.elapsedMs()))
                .append("  parallelism ")
                .append(metrics.parallelism());
        if (!status.isTerminal()) {
            out.append("  tasks running ").append(metrics.tasksRunning());
        }
        out.append('\n');
        out.append("Rows   read ")
                .append(count(metrics.rowsRead()))
                .append("  written ")
                .append(count(metrics.rowsWritten()))
                .append("  filtered ")
                .append(count(metrics.rowsFiltered()));
        if (metrics.rowsExpanded() > 0) {
            out.append("  expanded ").append(count(metrics.rowsExpanded()));
        }
        out.append("  in-flight ")
                .append(count(metrics.rowsInFlight()))
                .append("  failed ")
                .append(count(metrics.rowsFailed()))
                .append("   reconcile ")
                .append(metrics.reconciled() ? "OK" : "MISMATCH")
                .append('\n');
        out.append("Rate   read ")
                .append(rate(metrics.readRowsPerSecond()))
                .append("   write ")
                .append(rate(metrics.writeRowsPerSecond()))
                .append('\n');
        out.append("Wait   reader ")
                .append(duration(metrics.readerWaitMs()))
                .append(" (target slow)  writer ")
                .append(duration(metrics.writerWaitMs()))
                .append(" (source slow)  backpressured ")
                .append(duration(metrics.backpressuredMs()))
                .append(" (downstream slow)\n");
        if (!metrics.nodes().isEmpty()) {
            out.append('\n');
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"Node", "kind", "in rows", "out rows", "sel.", "in r/s", "self%"});
            java.util.Map<String, RunAnalysis.NodeRate> rates = new java.util.HashMap<>();
            analysis.rates().forEach(r -> rates.put(r.node(), r));
            for (NodeMetrics node : metrics.nodes()) {
                RunAnalysis.NodeRate rate = rates.get(node.node());
                rows.add(new String[] {
                    node.node(),
                    node.kind(),
                    count(node.rowsIn()),
                    count(node.rowsOut()),
                    node.kind().equals("transform") && node.selectivity() >= 0
                            ? String.format(Locale.ROOT, "%.2f", node.selectivity())
                            : "-",
                    node.kind().equals("source") || rate == null
                            ? "-"
                            : String.format(Locale.ROOT, "%,.0f", rate.rowsInPerSecond()),
                    node.selfTimeRatio() < 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", node.selfTimeRatio() * 100)
                });
            }
            appendTable(out, rows);
        }
        out.append('\n');
        appendBottleneck(out, analysis.bottleneck());
        return out.toString();
    }

    private static void appendBottleneck(StringBuilder out, Bottleneck bottleneck) {
        out.append("Bottleneck  ").append(bottleneck.kind());
        if (bottleneck.node() != null) {
            out.append(" at ").append(bottleneck.node());
        }
        switch (bottleneck.kind()) {
            case UNKNOWN -> out.append(": no task ran yet");
            case NONE -> out.append(": nothing waits for long");
            default -> {
                List<String> evidence = new ArrayList<>();
                bottleneck.evidence().forEach(e -> evidence.add(evidence(e)));
                out.append(": ").append(String.join("; ", evidence));
            }
        }
        out.append('\n');
        for (Bottleneck.Advice advice : bottleneck.advice()) {
            out.append("  → ").append(advice.text()).append('\n');
        }
    }

    /** A piece of evidence as words. */
    static String evidence(Bottleneck.Evidence evidence) {
        String share = String.format(Locale.ROOT, "%.0f%%", evidence.value() * 100);
        return switch (evidence.metric()) {
            case "readerWaitShare" -> "readers waited " + share + " of their time for the write queue";
            case "writerWaitShare" -> "write threads waited " + share + " of their time for rows";
            case "backpressuredShare" -> "tasks waited " + share + " of their time for network buffers";
            case "selfTimeRatio" -> evidence.node() + " busy " + share;
            case "exchangedBytes" -> String.format(Locale.ROOT, "%,.0f bytes exchanged", evidence.value());
            default -> evidence.metric() + (evidence.node() == null ? "" : " of " + evidence.node()) + " "
                    + evidence.value();
        };
    }

    private static void appendTable(StringBuilder out, List<String[]> rows) {
        int[] widths = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                line.append(row[i]);
                if (i < row.length - 1) {
                    line.append(" ".repeat(widths[i] - row[i].length() + 2));
                }
            }
            out.append(line.toString().stripTrailing()).append('\n');
        }
    }

    static String count(long value) {
        return value < 0 ? "-" : String.format(Locale.ROOT, "%,d", value);
    }

    static String rate(Rate rate) {
        return String.format(
                Locale.ROOT, "%,.0f r/s (avg %,.0f, peak %,.0f)", rate.current(), rate.average(), rate.peak());
    }

    static String duration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        if (ms < 60_000) {
            return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        }
        long seconds = ms / 1000;
        if (seconds < 3600) {
            return (seconds / 60) + "m" + (seconds % 60) + "s";
        }
        return (seconds / 3600) + "h" + (seconds % 3600 / 60) + "m";
    }
}
