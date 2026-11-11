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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            return JSON.writeValueAsString(toMap(plan)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the plan as JSON", e);
        }
    }

    /** The plan as maps and lists, the structure of the JSON output. */
    public static Map<String, Object> toMap(JobPlan plan) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("name", plan.name());
        job.put("description", plan.description());
        job.put("engine", plan.engine().name().toLowerCase(Locale.ROOT));
        job.put("mode", plan.mode().id());
        job.put("modeDerived", plan.modeDerived());
        job.put("delivery", plan.delivery().id());
        job.put("parallelism", plan.parallelism());
        job.put("timezone", plan.timeZone().getId());

        List<Object> nodes = new ArrayList<>();
        for (PlannedNode node : plan.nodes()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", node.id());
            map.put("kind", node.kind().id());
            map.put("type", node.type());
            map.put("plugin", node.plugin());
            map.put("inputs", node.inputs());
            map.put("inputsImplied", node.inputsImplied());
            map.put("parallelism", node.parallelism());
            map.put(
                    "boundedness",
                    node.boundedness() == null
                            ? null
                            : node.boundedness().name().toLowerCase(Locale.ROOT));
            List<Object> options = new ArrayList<>();
            for (PlannedOption option : node.options()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("key", option.key());
                o.put("value", option.value());
                o.put("origin", option.origin().id());
                o.put("sensitive", option.sensitive());
                options.add(o);
            }
            map.put("options", options);
            map.put(
                    "inputTables",
                    node.inputTables().stream().map(PlanExplainer::table).toList());
            map.put(
                    "outputTables",
                    node.outputTables().stream().map(PlanExplainer::table).toList());
            nodes.add(map);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("job", job);
        root.put("variables", plan.variables());
        root.put("nodes", nodes);
        root.put("warnings", plan.warnings());
        return root;
    }

    private static Map<String, Object> table(CatalogTable table) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", table.id().toString());
        List<Object> columns = new ArrayList<>();
        for (Column column : table.columns()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", column.name());
            c.put("type", column.type().nullable().toString());
            c.put("nullable", column.type().isNullable());
            if (column.comment() != null) {
                c.put("comment", column.comment());
            }
            columns.add(c);
        }
        map.put("columns", columns);
        map.put("primaryKey", table.primaryKey());
        map.put("partitionKeys", table.partitionKeys());
        return map;
    }
}
