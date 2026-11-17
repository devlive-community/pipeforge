/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/** The Markdown form of a benchmark report, for people; the JSON form is for tools and trends. */
final class BenchmarkReport {

    private BenchmarkReport() {}

    static String markdown(JsonNode report) {
        JsonNode environment = report.path("environment");
        JsonNode settings = report.path("settings");
        JsonNode result = report.path("result");
        JsonNode metrics = result.path("metrics");
        JsonNode bottleneck = result.path("bottleneck");
        StringBuilder text = new StringBuilder();
        text.append("# M1 benchmark: MySQL → PostgreSQL\n\n")
                .append(report.path("passed").asBoolean() ? "**Passed**" : "**Failed**")
                .append(" at ")
                .append(report.path("at").asText())
                .append(", commit `")
                .append(environment.path("commit").asText())
                .append("`.\n\n## Result\n\n| | |\n|---|---|\n");
        row(text, "Rows", number(settings.path("rows").asLong()));
        row(text, "Run", seconds(result.path("runSeconds").asDouble()));
        row(text, "Throughput", number(Math.round(result.path("rowsPerSecond").asDouble())) + " rows/s");
        row(
                text,
                "Per slot",
                number(Math.round(result.path("rowsPerSecondPerSlot").asDouble())) + " rows/s at parallelism "
                        + settings.path("parallelism").asInt());
        row(
                text,
                "Read, written",
                number(metrics.path("rowsRead").asLong()) + ", "
                        + number(metrics.path("rowsWritten").asLong()));
        row(
                text,
                "Splits read",
                metrics.path("splitsFinished").asInt() + " of "
                        + metrics.path("splitsTotal").asInt());
        row(
                text,
                "Bottleneck",
                bottleneck.path("kind").asText()
                        + (bottleneck.path("node").isTextual()
                                ? " at `" + bottleneck.path("node").asText() + "`"
                                : ""));
        row(
                text,
                "Dataset",
                (result.path("datasetReused").asBoolean() ? "reused" : "made in ")
                        + (result.path("datasetReused").asBoolean()
                                ? ""
                                : seconds(result.path("datasetLoadSeconds").asDouble())));
        text.append("\n## Checks\n\n| Check | Result | Detail |\n|---|---|---|\n");
        for (JsonNode check : report.path("checks")) {
            String outcome = check.path("passed").asBoolean()
                    ? "passed"
                    : check.path("informational").asBoolean() ? "not met (informational)" : "**failed**";
            text.append("| ")
                    .append(cell(check.path("name").asText()))
                    .append(" | ")
                    .append(outcome)
                    .append(" | ")
                    .append(cell(check.path("detail").asText()))
                    .append(" |\n");
        }
        if (bottleneck.path("advice").size() > 0) {
            text.append("\n## Advice of the run analysis\n\n");
            for (JsonNode advice : bottleneck.path("advice")) {
                text.append("- ").append(advice.path("text").asText()).append('\n');
            }
        }
        text.append(
                "\n## Timeline\n\n| Seconds | Read | Written | Read rows/s | Splits read |\n|---|---|---|---|---|\n");
        for (JsonNode sample : report.path("timeline")) {
            text.append(String.format(
                    Locale.ROOT,
                    "| %.0f | %s | %s | %s | %d of %d |%n",
                    sample.path("seconds").asDouble(),
                    number(sample.path("rowsRead").asLong()),
                    number(sample.path("rowsWritten").asLong()),
                    number(Math.round(sample.path("readRowsPerSecond").asDouble())),
                    sample.path("splitsFinished").asInt(),
                    sample.path("splitsTotal").asInt()));
        }
        text.append("\n## Environment\n\n| | |\n|---|---|\n");
        row(
                text,
                "Workers",
                settings.path("workers").asInt() + " × "
                        + settings.path("slotsPerWorker").asInt() + " slots");
        row(text, "Columns", String.valueOf(settings.path("columns").asInt()));
        row(text, "Splits asked", String.valueOf(settings.path("splits").asInt()));
        row(
                text,
                "Host",
                environment.path("os").asText() + ", "
                        + environment.path("cpus").asInt() + " CPUs, "
                        + Math.round(environment.path("memoryBytes").asDouble() / (1L << 30)) + " GiB");
        row(text, "Java", environment.path("java").asText());
        row(text, "MySQL", environment.path("mysql").asText());
        row(text, "PostgreSQL", environment.path("postgresql").asText());
        row(text, "Databases", environment.path("databases").asText());
        return text.toString();
    }

    private static void row(StringBuilder text, String name, String value) {
        text.append("| ").append(name).append(" | ").append(cell(value)).append(" |\n");
    }

    private static String cell(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    static String seconds(double seconds) {
        long whole = Math.round(seconds);
        return whole < 120
                ? String.format(Locale.ROOT, "%.1f s", seconds)
                : String.format(Locale.ROOT, "%d min %02d s", whole / 60, whole % 60);
    }
}
