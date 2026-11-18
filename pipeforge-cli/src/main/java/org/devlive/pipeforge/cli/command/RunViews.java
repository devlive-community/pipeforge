/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Messages;
import org.devlive.pipeforge.client.NodeMetrics;
import org.devlive.pipeforge.client.Run;
import org.devlive.pipeforge.client.RunMetrics;
import org.jspecify.annotations.Nullable;

/** How the run commands show a run of the cluster: its status, progress, rates and bottleneck. */
final class RunViews {

    private RunViews() {}

    static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    static String rate(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    /** A length of time as 850ms, 12.4s, 3m05s or 2h07m. */
    static String duration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        }
        if (millis < 60_000) {
            return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        }
        long seconds = millis / 1000;
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%dh%02dm", seconds / 3600, seconds / 60 % 60);
    }

    /** How long the run took, or has been running at {@code now}. */
    static long elapsed(Run run, Instant now) {
        if (run.startedAt() == null) {
            return 0;
        }
        Instant end = run.endedAt() != null ? run.endedAt() : now;
        return Math.max(0, Duration.between(run.startedAt(), end).toMillis());
    }

    /** The share of splits read, from 0 to 1, or -1 while the run has none. */
    static double progress(RunMetrics metrics) {
        return metrics.splitsTotal() > 0 ? (double) metrics.splitsFinished() / metrics.splitsTotal() : -1;
    }

    /** The time left, from the share done so far, or -1 while it cannot be told. */
    static long eta(long elapsedMs, double progress) {
        if (progress <= 0 || progress >= 1) {
            return -1;
        }
        return Math.round(elapsedMs * (1 - progress) / progress);
    }

    /** A bar of {@code width} cells for a share from 0 to 1. */
    static String bar(double share, int width) {
        int full = (int) Math.round(Math.max(0, Math.min(1, share)) * width);
        return "█".repeat(full) + "░".repeat(width - full);
    }

    /** The exit code a run of the cluster ends with. */
    static ExitCode exit(String status) {
        return switch (status) {
            case "SUCCESS" -> ExitCode.SUCCESS;
            case "CANCELLED" -> ExitCode.CANCELLED;
            case "TIMEOUT" -> ExitCode.TIMEOUT;
            default -> ExitCode.RUN_FAILED;
        };
    }

    /**
     * The status of a run as lines: the run, its progress, rows and rates, the bottleneck and its
     * advice, and its error.
     */
    static String status(Messages messages, Run run, RunMetrics metrics, @Nullable JsonNode analysis, Instant now) {
        List<String> lines = new ArrayList<>();
        long elapsed = elapsed(run, now);
        lines.add(messages.get("run.status.head", run.id(), run.job(), run.status(), duration(elapsed)));
        double progress = progress(metrics);
        if (progress >= 0) {
            long eta = run.endedAt() == null ? eta(elapsed, progress) : -1;
            lines.add(messages.get(
                            "run.status.progress",
                            metrics.splitsFinished(),
                            metrics.splitsTotal(),
                            String.format(Locale.ROOT, "%.0f%%", progress * 100))
                    + (eta >= 0 ? "  " + messages.get("run.status.eta", duration(eta)) : ""));
        }
        lines.add(messages.get("run.status.read", count(metrics.rowsRead()), rate(metrics.readRowsPerSecond())));
        lines.add(messages.get("run.status.written", count(metrics.rowsWritten()), rate(metrics.writeRowsPerSecond())));
        if (metrics.rowsDirty() > 0) {
            lines.add(messages.get("run.status.dirty", count(metrics.rowsDirty())));
        }
        lines.addAll(bottleneck(messages, analysis));
        if (run.error() != null) {
            lines.add("ERROR " + run.error().code() + " " + run.error().message());
        }
        return String.join("\n", lines) + "\n";
    }

    /** The bottleneck of the analysis and its advice, or nothing without one. */
    static List<String> bottleneck(Messages messages, @Nullable JsonNode analysis) {
        List<String> lines = new ArrayList<>();
        if (analysis == null) {
            return lines;
        }
        JsonNode bottleneck = analysis.path("bottleneck");
        String kind = bottleneck.path("kind").asText("");
        if (kind.isEmpty()) {
            return lines;
        }
        String node =
                bottleneck.path("node").isTextual() ? bottleneck.path("node").asText() : null;
        lines.add(messages.get("run.status.bottleneck", kind, messages.get("bottleneck." + kind))
                + (node == null ? "" : " (" + node + ")"));
        for (JsonNode advice : bottleneck.path("advice")) {
            lines.add("  → " + advice.path("text").asText());
        }
        return lines;
    }

    /** The counters of each node as a table. */
    static String nodes(Messages messages, List<NodeMetrics> nodes) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {
            messages.get("run.nodes.node"),
            messages.get("run.nodes.in"),
            messages.get("run.nodes.out"),
            messages.get("run.nodes.filtered"),
            messages.get("run.nodes.dirty")
        });
        for (NodeMetrics node : nodes) {
            rows.add(new String[] {
                node.node(),
                count(node.rowsIn()),
                count(node.rowsOut()),
                count(node.rowsFiltered()),
                count(node.rowsDirty())
            });
        }
        return table(rows);
    }

    /** Rows as columns of the width of their longest cell; the first column aligns left, the others right. */
    static String table(List<String[]> rows) {
        return table(rows, java.util.Set.of(0));
    }

    /** Rows as columns of the width of their longest cell; the {@code left} columns align left, the others right. */
    static String table(List<String[]> rows, java.util.Set<Integer> left) {
        int columns = rows.get(0).length;
        int[] widths = new int[columns];
        for (String[] row : rows) {
            for (int i = 0; i < columns; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        StringBuilder text = new StringBuilder();
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < columns; i++) {
                String cell = row[i];
                boolean right = !left.contains(i);
                String padded = right
                        ? " ".repeat(widths[i] - cell.length()) + cell
                        : cell + " ".repeat(widths[i] - cell.length());
                line.append(i == 0 ? "" : "  ").append(padded);
            }
            text.append(line.toString().stripTrailing()).append('\n');
        }
        return text.toString();
    }
}
