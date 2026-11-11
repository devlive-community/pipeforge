/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/** Reads and checks the options of a fake source node; problems are {@code PF-CONF-0002} with the reason. */
final class FakeConfig {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private FakeConfig() {}

    static FakeSettings read(ReadonlyConfig config, String node) {
        if (config.contains(FakeOptions.TABLE) && config.contains(FakeOptions.TABLES)) {
            throw invalid(node, FakeOptions.TABLES, config, "set either table or tables, not both");
        }
        List<Column> columns = columns(config, node);
        List<String> key = primaryKey(config, node, columns);
        List<TableId> ids = new ArrayList<>();
        if (config.contains(FakeOptions.TABLES)) {
            Set<TableId> seen = new LinkedHashSet<>();
            for (String name : config.get(FakeOptions.TABLES)) {
                TableId id = tableId(config, node, FakeOptions.TABLES, name);
                if (!seen.add(id)) {
                    throw invalid(node, FakeOptions.TABLES, config, "table " + id + " is listed twice");
                }
                ids.add(id);
            }
            if (ids.isEmpty()) {
                throw invalid(node, FakeOptions.TABLES, config, "list at least one table");
            }
        } else {
            ids.add(tableId(config, node, FakeOptions.TABLE, config.get(FakeOptions.TABLE)));
        }
        boolean unbounded = config.get(FakeOptions.UNBOUNDED);
        List<String> data = config.contains(FakeOptions.DATA) ? config.get(FakeOptions.DATA) : null;
        if (data != null) {
            if (config.contains(FakeOptions.ROWS)) {
                throw invalid(node, FakeOptions.DATA, config, "set either rows or data, not both");
            }
            if (ids.size() > 1) {
                throw invalid(node, FakeOptions.DATA, config, "literal rows need a single table");
            }
            if (unbounded) {
                throw invalid(node, FakeOptions.DATA, config, "literal rows cannot be unbounded");
            }
        }
        List<FakeTable> tables = new ArrayList<>();
        for (TableId id : ids) {
            CatalogTable table = CatalogTable.of(id, columns);
            if (!key.isEmpty()) {
                table = table.withPrimaryKey(key.toArray(String[]::new));
            }
            if (data != null) {
                List<Row> rows = rows(config, node, table, data);
                tables.add(new FakeTable(table, rows.size(), rows));
            } else {
                tables.add(new FakeTable(table, config.get(FakeOptions.ROWS), null));
            }
        }
        return new FakeSettings(
                tables,
                config.get(FakeOptions.SPLITS),
                config.get(FakeOptions.SEED),
                config.get(FakeOptions.NULL_RATIO),
                config.get(FakeOptions.BAD_ROW_RATIO),
                config.get(FakeOptions.ROWS_PER_SECOND),
                unbounded,
                eventTime(config, node, columns));
    }

    private static List<Column> columns(ReadonlyConfig config, String node) {
        List<Column> columns = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (String spec : config.get(FakeOptions.COLUMNS)) {
            Column column;
            try {
                column = column(spec);
            } catch (IllegalArgumentException e) {
                throw invalid(node, FakeOptions.COLUMNS, config, "'" + spec + "': " + e.getMessage());
            }
            if (!names.add(column.name())) {
                throw invalid(node, FakeOptions.COLUMNS, config, "column " + column.name() + " is listed twice");
            }
            columns.add(column);
        }
        if (columns.isEmpty()) {
            throw invalid(node, FakeOptions.COLUMNS, config, "list at least one column");
        }
        return columns;
    }

    /** {@code name TYPE}; the name may be quoted with backticks. */
    static Column column(String spec) {
        String text = spec.trim();
        String name;
        String type;
        if (text.startsWith("`")) {
            int end = text.indexOf('`', 1);
            if (end < 0) {
                throw new IllegalArgumentException("the quoted name has no closing backtick");
            }
            name = text.substring(1, end);
            type = text.substring(end + 1).trim();
        } else {
            int space = text.indexOf(' ');
            if (space < 0) {
                throw new IllegalArgumentException("write the column as 'name TYPE'");
            }
            name = text.substring(0, space);
            type = text.substring(space + 1).trim();
        }
        if (name.isEmpty() || type.isEmpty()) {
            throw new IllegalArgumentException("write the column as 'name TYPE'");
        }
        return Column.of(name, DataTypes.parse(type));
    }

    private static List<String> primaryKey(ReadonlyConfig config, String node, List<Column> columns) {
        List<String> key;
        if (config.contains(FakeOptions.PRIMARY_KEY)) {
            key = config.get(FakeOptions.PRIMARY_KEY);
        } else if (!config.contains(FakeOptions.COLUMNS)) {
            key = List.of("id");
        } else {
            key = List.of();
        }
        for (String name : key) {
            if (columns.stream().noneMatch(c -> c.name().equals(name))) {
                throw invalid(node, FakeOptions.PRIMARY_KEY, config, "there is no column " + name);
            }
        }
        return key;
    }

    private static TableId tableId(ReadonlyConfig config, String node, Option<?> option, String name) {
        try {
            return TableId.parse(name);
        } catch (IllegalArgumentException e) {
            throw invalid(node, option, config, "'" + name + "' is not a table name: " + e.getMessage());
        }
    }

    private static List<Row> rows(ReadonlyConfig config, String node, CatalogTable table, List<String> data) {
        List<Row> rows = new ArrayList<>(data.size());
        List<Column> columns = table.columns();
        for (int r = 0; r < data.size(); r++) {
            String where = "row " + (r + 1);
            JsonNode json;
            try {
                json = JSON.readTree(data.get(r));
            } catch (JsonProcessingException e) {
                throw invalid(node, FakeOptions.DATA, config, where + " is not JSON: " + e.getOriginalMessage());
            }
            if (json == null || !json.isArray() || json.size() != columns.size()) {
                throw invalid(
                        node,
                        FakeOptions.DATA,
                        config,
                        where + " must be a JSON array of " + columns.size() + " values (" + table.columnNames() + ")");
            }
            Object[] values = new Object[columns.size()];
            for (int c = 0; c < values.length; c++) {
                Column column = columns.get(c);
                try {
                    values[c] = ValueConverter.convert(json.get(c), column.type(), table.id());
                } catch (IllegalArgumentException e) {
                    throw invalid(
                            node,
                            FakeOptions.DATA,
                            config,
                            where + ", column " + column.name() + ": " + e.getMessage());
                }
            }
            rows.add(GenericRow.of(table.id(), values));
        }
        return rows;
    }

    private static FakeSettings.@Nullable EventTime eventTime(
            ReadonlyConfig config, String node, List<Column> columns) {
        if (!config.contains(FakeOptions.EVENT_TIME_COLUMN)) {
            return null;
        }
        String name = config.get(FakeOptions.EVENT_TIME_COLUMN);
        DataType type = columns.stream()
                .filter(c -> c.name().equals(name))
                .map(Column::type)
                .findFirst()
                .orElseThrow(() -> invalid(node, FakeOptions.EVENT_TIME_COLUMN, config, "there is no column " + name));
        TypeRoot root = type.root();
        if (root != TypeRoot.TIMESTAMP && root != TypeRoot.TIMESTAMP_LTZ && root != TypeRoot.INT64) {
            throw invalid(
                    node,
                    FakeOptions.EVENT_TIME_COLUMN,
                    config,
                    "column " + name + " is " + type + "; use TIMESTAMP, TIMESTAMP_LTZ or INT64");
        }
        Instant start;
        try {
            start = Instant.parse(config.get(FakeOptions.START_TIME));
        } catch (DateTimeParseException e) {
            throw invalid(node, FakeOptions.START_TIME, config, "expected an instant such as 2026-01-01T00:00:00Z");
        }
        return new FakeSettings.EventTime(
                name, start, config.get(FakeOptions.EVENT_TIME_STEP), config.get(FakeOptions.MAX_OUT_OF_ORDER));
    }

    private static PipeForgeException invalid(String node, Option<?> option, ReadonlyConfig config, String reason) {
        Object raw = config.keyOf(option).map(config::raw).orElse(null);
        return PipeForgeException.of(ErrorCode.OPTION_INVALID)
                .param("option", option.key())
                .param("node", node)
                .param("value", String.valueOf(raw))
                .param("reason", reason)
                .scope(ErrorScope.node(node))
                .build();
    }
}
