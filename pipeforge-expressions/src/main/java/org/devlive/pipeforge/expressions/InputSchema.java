/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;

/**
 * The columns an expression can read. Unquoted names match case-insensitively; quoted names
 * ({@code `Name`}) match exactly.
 */
public final class InputSchema {

    private final List<Column> columns;

    private InputSchema(List<Column> columns) {
        this.columns = List.copyOf(columns);
    }

    public static InputSchema of(List<Column> columns) {
        return new InputSchema(columns);
    }

    public static InputSchema of(CatalogTable table) {
        return new InputSchema(table.columns());
    }

    public List<Column> columns() {
        return columns;
    }

    /** Result of a lookup: the position, or a reason why the name does not resolve. */
    public record Lookup(int index, String problem) {
        public boolean found() {
            return index >= 0;
        }
    }

    public Lookup lookup(String name, boolean quoted) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return new Lookup(i, "");
            }
        }
        if (quoted) {
            return new Lookup(-1, "column `" + name + "` does not exist" + suggestion(name));
        }
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) {
                matches.add(i);
            }
        }
        if (matches.size() == 1) {
            return new Lookup(matches.get(0), "");
        }
        if (matches.size() > 1) {
            return new Lookup(-1, "column '" + name + "' is ambiguous; quote it with backticks to choose by case");
        }
        return new Lookup(-1, "column '" + name + "' does not exist" + suggestion(name));
    }

    private String suggestion(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        Optional<String> close = columns.stream()
                .map(Column::name)
                .filter(c -> distance(c.toLowerCase(Locale.ROOT), lower) <= 2)
                .min(java.util.Comparator.<String>comparingInt(c -> distance(c.toLowerCase(Locale.ROOT), lower))
                        .thenComparingInt(c -> distance(c, name)));
        return close.map(c -> "; did you mean '" + c + "'?").orElse("");
    }

    public DataType type(int index) {
        return columns.get(index).type();
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
