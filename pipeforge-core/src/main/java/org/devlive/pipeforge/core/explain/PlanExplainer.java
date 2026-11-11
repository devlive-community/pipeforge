/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.explain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.OptionOrigin;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.core.plan.PlannedOption;
import org.jspecify.annotations.Nullable;

/**
 * Renders a {@link JobPlan} for {@code explain}: the job settings and variables, the nodes in
 * topological order, the options with their origin, the schema each node produces (with the
 * changes against its input) and the warnings. Sensitive option values are masked. The output is
 * deterministic, so it can be compared with golden files.
 */
public final class PlanExplainer {

    private static final ObjectWriter JSON = new ObjectMapper()
            .writer(new DefaultPrettyPrinter()
                    .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                    .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    private PlanExplainer() {}

    public static String explain(JobPlan plan, ExplainFormat format) {
        return switch (format) {
            case TEXT -> text(plan);
            case JSON -> json(plan);
        };
    }

    // ------------------------------------------------------------------ text

    /** The plan as aligned text, ending with a line break. */
    public static String text(JobPlan plan) {
        StringBuilder out = new StringBuilder();
        out.append("Job ")
                .append(plan.name())
                .append("  mode=")
                .append(plan.mode().id())
                .append(plan.modeDerived() ? " (derived)" : "")
                .append("  delivery=")
                .append(plan.delivery().id())
                .append("  engine=")
                .append(plan.engine().name().toLowerCase(Locale.ROOT))
                .append("  parallelism=")
                .append(plan.parallelism())
                .append("  timezone=")
                .append(plan.timeZone().getId())
                .append('\n');
        if (plan.description() != null) {
            out.append("Description  ").append(plan.description()).append('\n');
        }
        out.append("Variables  ");
        if (plan.variables().isEmpty()) {
            out.append("none");
        } else {
            List<String> pairs = new ArrayList<>();
            plan.variables().forEach((name, value) -> pairs.add(name + "=" + value));
            out.append(String.join("  ", pairs));
        }
        out.append("\n\n");

        out.append("Nodes (").append(plan.nodes().size()).append(", in topological order)\n");
        Table nodes = new Table();
        for (PlannedNode node : plan.nodes()) {
            String detail = node.kind() == NodeKind.SOURCE
                    ? node.boundedness() == null
                            ? ""
                            : node.boundedness().name().toLowerCase(Locale.ROOT)
                    : "inputs=" + String.join(",", node.inputs()) + (node.inputsImplied() ? " (implied)" : "");
            nodes.row(
                    "  [" + node.id() + "]",
                    node.kind().id(),
                    node.type(),
                    node.plugin(),
                    "parallelism=" + node.parallelism(),
                    detail);
        }
        nodes.appendTo(out);
        out.append('\n');

        out.append("Options (defaults omitted)\n");
        Table options = new Table();
        for (PlannedNode node : plan.nodes()) {
            List<PlannedOption> set = node.options().stream()
                    .filter(o -> o.origin() != OptionOrigin.DEFAULT)
                    .toList();
            String label = "  [" + node.id() + "]";
            if (set.isEmpty()) {
                options.row(label, "none", "");
            }
            for (PlannedOption option : set) {
                options.row(
                        label,
                        option.key() + "=" + display(option.value()),
                        option.origin().id());
                label = "";
            }
        }
        options.appendTo(out);
        out.append('\n');

        out.append("Schema\n");
        for (PlannedNode node : plan.nodes()) {
            if (node.kind() == NodeKind.SINK) {
                // A sink shows what it receives only when several inputs were merged.
                if (node.inputs().size() > 1) {
                    for (CatalogTable table : node.inputTables()) {
                        out.append("  [").append(node.id()).append("] <- ").append(table.id());
                        appendKey(out, table);
                        out.append("  (merged from ")
                                .append(String.join(", ", node.inputs()))
                                .append(")\n");
                        appendColumns(out, table);
                    }
                }
                continue;
            }
            for (CatalogTable table : node.outputTables()) {
                out.append("  [").append(node.id()).append("] -> ").append(table.id());
                appendKey(out, table);
                if (node.kind() != NodeKind.SOURCE) {
                    out.append("  ").append(changes(node, table));
                }
                out.append('\n');
                appendColumns(out, table);
            }
        }
        out.append('\n');

        out.append("Warnings");
        if (plan.warnings().isEmpty()) {
            out.append("  none\n");
        } else {
            out.append('\n');
            plan.warnings().forEach(w -> out.append("  - ").append(w).append('\n'));
        }
        return out.toString();
    }

    private static void appendKey(StringBuilder out, CatalogTable table) {
        if (!table.primaryKey().isEmpty()) {
            out.append("  primary key (")
                    .append(String.join(", ", table.primaryKey()))
                    .append(')');
        }
    }

    private static void appendColumns(StringBuilder out, CatalogTable table) {
        Table columns = new Table();
        for (Column column : table.columns()) {
            columns.row(
                    "      " + column.name(),
                    column.type().toString(),
                    column.comment() == null ? "" : "-- " + column.comment());
        }
        columns.appendTo(out);
    }

    /** What a transform changed in a table compared with the table of the same id it received. */
    private static String changes(PlannedNode node, CatalogTable table) {
        Optional<CatalogTable> input = node.inputTables().stream()
                .filter(t -> t.id().equals(table.id()))
                .findFirst();
        if (input.isEmpty()) {
            return "(new table)";
        }
        List<String> changes = new ArrayList<>();
        for (Column column : table.columns()) {
            Optional<Column> before = input.get().column(column.name());
            if (before.isEmpty()) {
                changes.add("+" + column.name());
            } else if (!before.get().type().equals(column.type())) {
                changes.add(column.name() + " " + before.get().type() + " -> " + column.type());
            }
        }
        for (Column column : input.get().columns()) {
            if (table.column(column.name()).isEmpty()) {
                changes.add("-" + column.name());
            }
        }
        if (changes.isEmpty() && !input.get().columnNames().equals(table.columnNames())) {
            changes.add("columns reordered");
        }
        return changes.isEmpty() ? "(unchanged)" : "(" + String.join(", ", changes) + ")";
    }

    private static String display(@Nullable Object value) {
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            list.forEach(item -> items.add(display(item)));
            return "[" + String.join(", ", items) + "]";
        }
        return String.valueOf(value);
    }

    /** Columns padded to the widest cell; trailing blanks are trimmed. */
    private static final class Table {
        private final List<String[]> rows = new ArrayList<>();

        void row(String... cells) {
            rows.add(cells);
        }

        void appendTo(StringBuilder out) {
            int columns = rows.stream().mapToInt(r -> r.length).max().orElse(0);
            int[] widths = new int[columns];
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
    }

    // ------------------------------------------------------------------ JSON

    /** The complete plan as JSON, ending with a line break. */
    public static String json(JobPlan plan) {
        try {
            return JSON.writeValueAsString(document(plan)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the plan as JSON", e);
        }
    }

    /** The plan as a structured document, the content of the JSON output. */
    public static ExplainDocument document(JobPlan plan) {
        ExplainDocument.Job job = new ExplainDocument.Job(
                plan.name(),
                plan.description(),
                plan.engine().name().toLowerCase(Locale.ROOT),
                plan.mode().id(),
                plan.modeDerived(),
                plan.delivery().id(),
                plan.parallelism(),
                plan.timeZone().getId());
        List<ExplainDocument.Node> nodes = new ArrayList<>();
        for (PlannedNode node : plan.nodes()) {
            nodes.add(new ExplainDocument.Node(
                    node.id(),
                    node.kind().id(),
                    node.type(),
                    node.plugin(),
                    node.inputs(),
                    node.inputsImplied(),
                    node.parallelism(),
                    node.boundedness() == null
                            ? null
                            : node.boundedness().name().toLowerCase(Locale.ROOT),
                    node.options().stream()
                            .map(o -> new ExplainDocument.Option(
                                    o.key(), o.value(), o.origin().id(), o.sensitive()))
                            .toList(),
                    node.inputTables().stream().map(PlanExplainer::table).toList(),
                    node.outputTables().stream().map(PlanExplainer::table).toList()));
        }
        return new ExplainDocument(job, plan.variables(), nodes, plan.warnings());
    }

    private static ExplainDocument.Table table(CatalogTable table) {
        List<ExplainDocument.Column> columns = new ArrayList<>();
        for (Column column : table.columns()) {
            columns.add(new ExplainDocument.Column(
                    column.name(),
                    column.type().nullable().toString(),
                    column.type().isNullable(),
                    column.comment()));
        }
        return new ExplainDocument.Table(table.id().toString(), columns, table.primaryKey(), table.partitionKeys());
    }
}
