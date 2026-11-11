/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.RowKind;
import org.jspecify.annotations.Nullable;
import org.opentest4j.AssertionFailedError;

/**
 * Compares rows by value, in any order. Values are compared by meaning rather than by Java
 * identity: {@code 1.50} equals {@code 1.5} for decimals, byte arrays by content, maps regardless of
 * their order, nested rows by their fields. Floating point values must be exactly equal.
 */
public final class RowAssertions {

    private static final int SHOWN = 10;

    private RowAssertions() {}

    /**
     * Fails unless {@code actual} holds the same rows as {@code expected}, each as many times.
     *
     * @param what what the rows are, for the message, e.g. "rows written by the sink"
     */
    public static void assertSameRows(String what, List<Row> expected, List<Row> actual) {
        Map<String, Integer> missing = count(expected);
        Map<String, Integer> unexpected = new LinkedHashMap<>();
        for (Row row : actual) {
            String key = canonical(row);
            Integer left = missing.get(key);
            if (left == null) {
                unexpected.merge(key, 1, Integer::sum);
            } else if (left == 1) {
                missing.remove(key);
            } else {
                missing.put(key, left - 1);
            }
        }
        if (missing.isEmpty() && unexpected.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder()
                .append(what)
                .append(": expected ")
                .append(expected.size())
                .append(" row(s) but got ")
                .append(actual.size());
        describe(message, "missing", missing);
        describe(message, "unexpected", unexpected);
        throw new AssertionFailedError(message.toString());
    }

    /** A string that is equal for two rows exactly when their kind and values are equal by meaning. */
    public static String canonical(Row row) {
        List<String> values = new ArrayList<>(row.arity());
        for (int i = 0; i < row.arity(); i++) {
            values.add(canonical(row.get(i)));
        }
        String kind = row.kind() == RowKind.INSERT ? "" : row.kind() + " ";
        return kind + "(" + String.join(", ", values) + ")";
    }

    static String canonical(@Nullable Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof byte[] bytes) {
            return "0x" + Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Float f) {
            return "f" + Float.floatToIntBits(f);
        }
        if (value instanceof Double d) {
            return "d" + Double.doubleToLongBits(d);
        }
        if (value instanceof String text) {
            return "'" + text.replace("'", "''") + "'";
        }
        if (value instanceof Row row) {
            return canonical(row);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, String> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(canonical(k), canonical(v)));
            return "{"
                    + String.join(
                            ", ",
                            sorted.entrySet().stream()
                                    .map(e -> e.getKey() + ": " + e.getValue())
                                    .toList()) + "}";
        }
        if (value instanceof Collection<?> list) {
            return "["
                    + String.join(
                            ", ", list.stream().map(RowAssertions::canonical).toList()) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.getClass().getSimpleName().charAt(0) + value.toString();
        }
        return value.getClass().getSimpleName() + ":" + value;
    }

    private static Map<String, Integer> count(List<Row> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (Row row : rows) {
            counts.merge(canonical(row), 1, Integer::sum);
        }
        return counts;
    }

    private static void describe(StringBuilder message, String label, Map<String, Integer> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int total = rows.values().stream().mapToInt(Integer::intValue).sum();
        message.append("\n  ").append(label).append(" (").append(total).append("):");
        rows.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(SHOWN).forEach(e -> {
            String text = e.getKey();
            if (text.length() > 200) {
                text = text.substring(0, 200) + "…";
            }
            message.append("\n    ").append(text).append(e.getValue() > 1 ? " ×" + e.getValue() : "");
        });
        if (rows.size() > SHOWN) {
            message.append("\n    … and ").append(rows.size() - SHOWN).append(" more");
        }
    }
}
