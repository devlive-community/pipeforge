/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.devlive.pipeforge.api.type.DataType;
import org.jspecify.annotations.Nullable;

/**
 * One entry of {@code mapping}: an output column taken from an input column ({@code from}), computed
 * ({@code expr}) or constant ({@code value}), with an optional name, type, nullability, default and
 * comment.
 *
 * @param index the position in {@code mapping}, from 1, for messages
 */
record MappingEntry(
        int index,
        @Nullable String from,
        @Nullable String to,
        @Nullable String expr,
        @Nullable String value,
        @Nullable DataType type,
        @Nullable Boolean nullable,
        @Nullable String defaultValue,
        @Nullable String comment) {

    /** The keys an entry may have. */
    static final List<String> KEYS = List.of("from", "to", "expr", "value", "type", "nullable", "default", "comment");

    /** Reads an entry; problems are reported by {@code invalid} with the reason. */
    static MappingEntry read(int index, Map<String, String> fields, Problems invalid) {
        for (String key : fields.keySet()) {
            if (!KEYS.contains(key)) {
                String close = KEYS.stream()
                        .filter(k -> Names.distance(k, key.toLowerCase(Locale.ROOT)) <= 2)
                        .findFirst()
                        .map(k -> "; did you mean '" + k + "'?")
                        .orElse("");
                throw invalid.of("entry " + index + ": unknown key '" + key + "'" + close + " (keys: "
                        + String.join(", ", KEYS) + ")");
            }
        }
        String from = text(fields.get("from"));
        String expr = text(fields.get("expr"));
        String value = fields.get("value");
        String to = text(fields.get("to"));
        if (from == null && expr == null && value == null) {
            throw invalid.of("entry " + index + ": give from, expr or value");
        }
        if (value != null && (from != null || expr != null)) {
            throw invalid.of("entry " + index + ": a constant value cannot be combined with from or expr");
        }
        if (from == null && to == null) {
            throw invalid.of("entry " + index + ": a computed or constant column needs a name in to");
        }
        DataType type = null;
        String typeText = text(fields.get("type"));
        if (typeText != null) {
            try {
                type = org.devlive.pipeforge.api.type.DataTypes.parse(typeText).withNullability(true);
            } catch (IllegalArgumentException e) {
                throw invalid.of("entry " + index + ": '" + typeText + "' is not a type: " + e.getMessage());
            }
        }
        Boolean nullable = null;
        String nullableText = text(fields.get("nullable"));
        if (nullableText != null) {
            nullable = switch (nullableText.toLowerCase(Locale.ROOT)) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> throw invalid.of("entry " + index + ": nullable must be true or false");
            };
        }
        return new MappingEntry(
                index, from, to, expr, value, type, nullable, fields.get("default"), text(fields.get("comment")));
    }

    private static @Nullable String text(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /** How this entry is described in messages: {@code entry 2 (to: amount_yuan)}. */
    String label() {
        return "entry " + index + (to != null ? " (to: " + to + ")" : from != null ? " (from: " + from + ")" : "");
    }

    /** Raises option errors with a reason. */
    @FunctionalInterface
    interface Problems {
        RuntimeException of(String reason);
    }
}
