/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.RowKind;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.jspecify.annotations.Nullable;

/** Reads the JSON lines of the console sink back into rows, as a consumer of the output would. */
final class JsonRows {

    static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonRows() {}

    /** The rows of {@code table} among the lines; other lines (notices, schema changes) are skipped. */
    static List<Row> read(List<String> lines, CatalogTable table) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            JsonNode node = MAPPER.readTree(line);
            if (!node.has("row")
                    || !node.get("table").asText().equals(table.id().toQualifiedName())) {
                continue;
            }
            List<Column> columns = table.columns();
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                values[i] = value(
                        node.get("row").get(columns.get(i).name()),
                        columns.get(i).type());
            }
            RowKind kind = kind(node.path("kind").asText("+I"));
            rows.add(GenericRow.ofKind(table.id(), kind, values));
        }
        return rows;
    }

    static RowKind kind(String shortName) {
        for (RowKind kind : RowKind.values()) {
            if (kind.shortName().equals(shortName)) {
                return kind;
            }
        }
        throw new IllegalArgumentException(shortName);
    }

    static @Nullable Object value(@Nullable JsonNode node, DataType type) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        return switch (type.root()) {
            case BOOLEAN -> node.booleanValue();
            case INT8 -> (byte) node.intValue();
            case INT16 -> (short) node.intValue();
            case INT32 -> node.intValue();
            case INT64 -> node.longValue();
            case FLOAT32 -> node.isTextual() ? Float.parseFloat(node.textValue()) : node.floatValue();
            case FLOAT64 -> node.isTextual() ? Double.parseDouble(node.textValue()) : node.doubleValue();
            case DECIMAL -> node.decimalValue();
            case STRING -> node.textValue();
            case JSON -> MAPPER.writeValueAsString(node);
            case BYTES -> Base64.getDecoder().decode(node.textValue());
            case DATE -> LocalDate.parse(node.textValue(), Values.date());
            case TIME -> LocalTime.parse(node.textValue(), Values.time(type.precision()));
            case TIMESTAMP -> LocalDateTime.parse(node.textValue(), Values.timestamp(type.precision(), false));
            case TIMESTAMP_LTZ -> OffsetDateTime.parse(node.textValue(), Values.timestamp(type.precision(), true))
                    .toInstant();
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (JsonNode item : node) {
                    list.add(value(item, type.elementType()));
                }
                yield list;
            }
            case MAP -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                if (node.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        map.put(field.getKey(), value(field.getValue(), type.valueType()));
                    }
                } else {
                    for (JsonNode pair : node) {
                        map.put(value(pair.get(0), type.keyType()), value(pair.get(1), type.valueType()));
                    }
                }
                yield map;
            }
            case ROW -> {
                Object[] values = new Object[type.fields().size()];
                for (int i = 0; i < values.length; i++) {
                    DataType.Field field = type.fields().get(i);
                    values[i] = value(node.get(field.name()), field.type());
                }
                yield GenericRow.of(TableId.of("row"), values);
            }
            case NULL -> null;
        };
    }
}
