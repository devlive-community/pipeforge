/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.jspecify.annotations.Nullable;

/**
 * Converts the JSON values of literal rows to the Java values of their column types. Throws {@link
 * IllegalArgumentException} with the reason when a value does not fit.
 */
final class ValueConverter {

    private ValueConverter() {}

    static @Nullable Object convert(JsonNode node, DataType type, TableId table) {
        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        return switch (type.root()) {
            case BOOLEAN -> {
                if (node.isBoolean()) {
                    yield node.booleanValue();
                }
                String text = node.asText();
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("expected true or false but got " + node);
                }
                yield Boolean.parseBoolean(text);
            }
            case INT8 -> (byte) integer(node, Byte.MIN_VALUE, Byte.MAX_VALUE);
            case INT16 -> (short) integer(node, Short.MIN_VALUE, Short.MAX_VALUE);
            case INT32 -> (int) integer(node, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case INT64 -> integer(node, Long.MIN_VALUE, Long.MAX_VALUE);
            case FLOAT32 -> (float) floating(node);
            case FLOAT64 -> floating(node);
            case DECIMAL -> decimal(node, type);
            case STRING -> node.isTextual() ? node.textValue() : node.toString();
            case JSON -> node.isTextual() ? node.textValue() : node.toString();
            case BYTES -> {
                try {
                    yield Base64.getDecoder().decode(text(node));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("expected Base64 but got " + node, e);
                }
            }
            case DATE -> parse(node, () -> LocalDate.parse(text(node)));
            case TIME -> parse(node, () -> LocalTime.parse(text(node)));
            case TIMESTAMP -> parse(node, () -> LocalDateTime.parse(text(node).replace(' ', 'T')));
            case TIMESTAMP_LTZ -> {
                if (node.isIntegralNumber()) {
                    yield Instant.ofEpochMilli(node.longValue());
                }
                String text = text(node);
                yield parse(
                        node,
                        () -> text.endsWith("Z") || text.endsWith("z")
                                ? Instant.parse(text)
                                : OffsetDateTime.parse(text).toInstant());
            }
            case ARRAY -> {
                if (!node.isArray()) {
                    throw new IllegalArgumentException("expected an array but got " + node);
                }
                List<Object> list = new ArrayList<>(node.size());
                for (JsonNode element : node) {
                    list.add(convert(element, type.elementType(), table));
                }
                yield list;
            }
            case MAP -> {
                if (!node.isObject()) {
                    throw new IllegalArgumentException("expected an object but got " + node);
                }
                Map<Object, Object> map = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    map.put(
                            convert(new TextNode(field.getKey()), type.keyType(), table),
                            convert(field.getValue(), type.valueType(), table));
                }
                yield map;
            }
            case ROW -> {
                List<DataType.Field> fields = type.fields();
                Object[] values = new Object[fields.size()];
                if (node.isArray()) {
                    if (node.size() != fields.size()) {
                        throw new IllegalArgumentException(
                                "expected " + fields.size() + " field values but got " + node.size());
                    }
                    for (int i = 0; i < values.length; i++) {
                        values[i] = convert(node.get(i), fields.get(i).type(), table);
                    }
                } else if (node.isObject()) {
                    for (int i = 0; i < values.length; i++) {
                        values[i] = convert(
                                node.path(fields.get(i).name()), fields.get(i).type(), table);
                    }
                } else {
                    throw new IllegalArgumentException("expected an array or an object but got " + node);
                }
                yield GenericRow.of(table, values);
            }
            case NULL -> throw new IllegalArgumentException("a NULL column only holds null");
        };
    }

    private static long integer(JsonNode node, long min, long max) {
        long value;
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            value = node.longValue();
        } else if (node.isTextual()) {
            try {
                value = Long.parseLong(node.textValue().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("expected an integer but got " + node, e);
            }
        } else {
            throw new IllegalArgumentException("expected an integer but got " + node);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(value + " is outside " + min + " .. " + max);
        }
        return value;
    }

    private static double floating(JsonNode node) {
        if (node.isNumber()) {
            return node.doubleValue();
        }
        try {
            return Double.parseDouble(text(node));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a number but got " + node, e);
        }
    }

    private static BigDecimal decimal(JsonNode node, DataType type) {
        BigDecimal value;
        try {
            value = node.isNumber()
                    ? node.decimalValue()
                    : new BigDecimal(text(node).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a decimal but got " + node, e);
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(type.scale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(value + " has more than " + type.scale() + " decimal places", e);
        }
        if (scaled.precision() - scaled.scale() > type.precision() - type.scale()) {
            throw new IllegalArgumentException(value + " does not fit " + type);
        }
        return scaled;
    }

    private static String text(JsonNode node) {
        if (!node.isTextual()) {
            throw new IllegalArgumentException("expected a string but got " + node);
        }
        return node.textValue();
    }

    private interface Parse<T> {
        T run();
    }

    private static <T> T parse(JsonNode node, Parse<T> action) {
        try {
            return action.run();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("cannot read " + node + ": " + e.getMessage(), e);
        }
    }
}
