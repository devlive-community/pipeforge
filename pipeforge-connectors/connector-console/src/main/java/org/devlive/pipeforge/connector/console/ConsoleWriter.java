/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.table.AddColumn;
import org.devlive.pipeforge.api.table.AlterColumnComment;
import org.devlive.pipeforge.api.table.AlterColumnType;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.table.CreateTable;
import org.devlive.pipeforge.api.table.DropColumn;
import org.devlive.pipeforge.api.table.DropTable;
import org.devlive.pipeforge.api.table.RenameColumn;
import org.devlive.pipeforge.api.table.RenameTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.api.table.TruncateTable;
import org.devlive.pipeforge.api.type.DataType;
import org.jspecify.annotations.Nullable;

/**
 * Prints the rows of one parallel instance. Tables are buffered per input table and printed when
 * they reach the batch size, at every checkpoint and at the end; JSON lines are printed at once.
 * Every table, line and notice is printed with a single call, so the output of parallel instances
 * never interleaves within one.
 */
final class ConsoleWriter implements SinkWriter<Void, Void> {

    private final ConsoleSettings settings;
    private final Values values;
    private final PrintStream out;
    private final String subtask;
    private final Map<TableId, CatalogTable> tables = new LinkedHashMap<>();
    private final Map<TableId, List<String[]>> pending = new LinkedHashMap<>();
    private long printed;
    private long skipped;

    ConsoleWriter(
            ConsoleSettings settings,
            List<CatalogTable> inputs,
            Values values,
            PrintStream out,
            int index,
            int parallelism) {
        this.settings = settings;
        this.values = values;
        this.out = out;
        this.subtask = parallelism > 1 ? "subtask " + (index + 1) + "/" + parallelism : "";
        inputs.forEach(table -> tables.put(table.id(), table));
    }

    @Override
    public void write(Row row) throws IOException {
        if (settings.limit() >= 0 && printed >= settings.limit()) {
            if (skipped++ == 0) {
                // The rows printed so far come first, then the notice about the ones that follow.
                printPending();
                notice("limit of " + settings.limit() + " row(s) reached" + where()
                        + "; further rows are counted but not printed");
            }
            return;
        }
        printed++;
        CatalogTable table = table(row);
        if (settings.format() == ConsoleOptions.Format.JSON) {
            out.print(jsonLine(row, table));
            return;
        }
        List<Column> columns = table.columns();
        int offset = settings.printKind() ? 1 : 0;
        String[] cells = new String[columns.size() + offset];
        if (offset == 1) {
            cells[0] = row.kind().shortName();
        }
        for (int i = 0; i < columns.size(); i++) {
            Object value = i < row.arity() ? row.get(i) : null;
            cells[i + offset] =
                    TableBlock.cell(values.text(value, known(columns.get(i).type())), settings.maxWidth());
        }
        List<String[]> rows = pending.computeIfAbsent(table.id(), id -> new ArrayList<>());
        rows.add(cells);
        if (rows.size() >= settings.batchSize()) {
            print(table.id());
        }
    }

    /** The schema of a row's table; tables that were not announced get columns c1, c2, ... of unknown type. */
    private CatalogTable table(Row row) {
        CatalogTable table = tables.get(row.table());
        if (table != null && table.columns().size() >= row.arity()) {
            return table;
        }
        List<Column> columns = new ArrayList<>(table == null ? List.of() : table.columns());
        for (int i = columns.size(); i < row.arity(); i++) {
            columns.add(Column.of("c" + (i + 1), org.devlive.pipeforge.api.type.DataTypes.NULL()));
        }
        CatalogTable known = CatalogTable.of(row.table(), columns);
        tables.put(row.table(), known);
        return known;
    }

    private String jsonLine(Row row, CatalogTable table) throws IOException {
        StringWriter text = new StringWriter();
        try (JsonGenerator json = Values.generator(text)) {
            json.writeStartObject();
            json.writeStringField("table", table.id().toQualifiedName());
            if (settings.printKind()) {
                json.writeStringField("kind", row.kind().shortName());
            }
            json.writeFieldName("row");
            json.writeStartObject();
            List<Column> columns = table.columns();
            for (int i = 0; i < columns.size(); i++) {
                json.writeFieldName(columns.get(i).name());
                values.write(
                        json,
                        i < row.arity() ? row.get(i) : null,
                        known(columns.get(i).type()));
            }
            json.writeEndObject();
            json.writeEndObject();
        }
        return text.append('\n').toString();
    }

    private static @Nullable DataType known(DataType type) {
        return type.root() == org.devlive.pipeforge.api.type.TypeRoot.NULL ? null : type;
    }

    private void print(TableId id) {
        List<String[]> rows = pending.remove(id);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        CatalogTable table = tables.get(id);
        List<String> headers = new ArrayList<>();
        List<Boolean> right = new ArrayList<>();
        if (settings.printKind()) {
            headers.add("op");
            right.add(false);
        }
        for (Column column : table.columns()) {
            headers.add(column.name());
            right.add(Values.numeric(known(column.type())));
        }
        String title = id.toQualifiedName() + (subtask.isEmpty() ? "" : " [" + subtask + "]");
        out.print(TableBlock.render(title, headers, right, rows));
    }

    @Override
    public void applySchemaChange(SchemaChangeEvent event) {
        print(event.table());
        CatalogTable table = tables.get(event.table());
        String change = describe(event);
        switch (event) {
            case CreateTable create -> tables.put(create.definition().id(), create.definition());
            case DropTable drop -> tables.remove(drop.table());
            case RenameTable rename -> {
                if (table != null) {
                    tables.remove(rename.table());
                    tables.put(rename.newId(), table.withId(rename.newId()));
                }
            }
            case TruncateTable truncate -> {}
            case AddColumn add -> {
                if (table != null) {
                    List<Column> columns = new ArrayList<>(table.columns());
                    int after = add.afterColumn() == null ? columns.size() - 1 : table.indexOf(add.afterColumn());
                    columns.add(after < 0 ? columns.size() : after + 1, add.column());
                    tables.put(table.id(), table.withColumns(columns));
                }
            }
            case DropColumn dropColumn -> update(table, dropColumn.column(), null);
            case RenameColumn renameColumn -> {
                Column column = table == null
                        ? null
                        : table.column(renameColumn.column()).orElse(null);
                update(table, renameColumn.column(), column == null ? null : column.withName(renameColumn.newName()));
            }
            case AlterColumnType alter -> {
                Column column =
                        table == null ? null : table.column(alter.column()).orElse(null);
                update(table, alter.column(), column == null ? null : column.withType(alter.newType()));
            }
            case AlterColumnComment comment -> {}
        }
        if (settings.format() == ConsoleOptions.Format.JSON) {
            out.print(jsonObject("table", event.table().toQualifiedName(), "schema-change", change));
        } else {
            out.print("-- " + event.table().toQualifiedName() + ": " + change + where() + "\n");
        }
    }

    /** Replaces or, with null, drops a column. */
    private void update(@Nullable CatalogTable table, String name, @Nullable Column replacement) {
        if (table == null || table.indexOf(name) < 0) {
            return;
        }
        List<Column> columns = new ArrayList<>(table.columns());
        int index = table.indexOf(name);
        if (replacement == null) {
            columns.remove(index);
        } else {
            columns.set(index, replacement);
        }
        tables.put(table.id(), table.withColumns(columns));
    }

    static String describe(SchemaChangeEvent event) {
        return switch (event) {
            case CreateTable create -> "create table with "
                    + create.definition().columns().size() + " column(s)";
            case DropTable drop -> "drop table";
            case RenameTable rename -> "rename table to " + rename.newId().toQualifiedName();
            case TruncateTable truncate -> "truncate table";
            case AddColumn add -> "add column " + add.column().name() + " "
                    + add.column().type() + (add.afterColumn() == null ? "" : " after " + add.afterColumn());
            case DropColumn drop -> "drop column " + drop.column();
            case RenameColumn rename -> "rename column " + rename.column() + " to " + rename.newName();
            case AlterColumnType alter -> "change column " + alter.column() + " to " + alter.newType();
            case AlterColumnComment comment -> "change the comment of column " + comment.column();
        };
    }

    @Override
    public void flush() {
        printPending();
        out.flush();
    }

    private void printPending() {
        for (TableId id : new ArrayList<>(pending.keySet())) {
            print(id);
        }
    }

    @Override
    public void close() {
        flush();
        if (skipped > 0) {
            notice(skipped + " row(s) not printed" + where() + " (limit " + settings.limit() + ")");
            out.flush();
        }
    }

    private String where() {
        return subtask.isEmpty() ? "" : " in " + subtask;
    }

    /** A message about the output itself: a line of text, or a JSON object in the JSON format. */
    private void notice(String message) {
        if (settings.format() == ConsoleOptions.Format.JSON) {
            out.print(jsonObject("notice", message, null, null));
        } else {
            out.print("-- " + message + "\n");
        }
    }

    private static String jsonObject(String key, String value, @Nullable String key2, @Nullable String value2) {
        StringWriter text = new StringWriter();
        try (JsonGenerator json = Values.generator(text)) {
            json.writeStartObject();
            json.writeStringField(key, value);
            if (key2 != null) {
                json.writeStringField(key2, value2);
            }
            json.writeEndObject();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return text.append('\n').toString();
    }

    long printed() {
        return printed;
    }

    long skipped() {
        return skipped;
    }
}
