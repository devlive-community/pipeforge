/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.NameCase;

/** Column name styles and lookups. */
final class Names {

    private Names() {}

    /** {@code name} written in {@code style}. */
    static String apply(NameCase style, String name) {
        if (words(name).isEmpty()) {
            return name;
        }
        return switch (style) {
            case KEEP -> name;
            case LOWER -> name.toLowerCase(Locale.ROOT);
            case UPPER -> name.toUpperCase(Locale.ROOT);
            case SNAKE -> String.join("_", words(name)).toLowerCase(Locale.ROOT);
            case CAMEL -> camel(words(name));
        };
    }

    private static String camel(List<String> words) {
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            String lower = word.toLowerCase(Locale.ROOT);
            if (out.isEmpty()) {
                out.append(lower);
            } else if (!lower.isEmpty()) {
                out.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
            }
        }
        return out.toString();
    }

    /**
     * The words of a name: separated by {@code _}, {@code -}, spaces and dots, and by case changes
     * ({@code createdAt}, {@code HTTPServer} → {@code HTTP}, {@code Server}).
     */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int length = name.length();
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || c == ' ' || c == '.') {
                flush(word, words);
                continue;
            }
            if (!word.isEmpty() && Character.isUpperCase(c)) {
                char previous = word.charAt(word.length() - 1);
                boolean nextLower = i + 1 < length && Character.isLowerCase(name.charAt(i + 1));
                if (Character.isLowerCase(previous)
                        || Character.isDigit(previous)
                        || (Character.isUpperCase(previous) && nextLower)) {
                    flush(word, words);
                }
            }
            word.append(c);
        }
        flush(word, words);
        return words;
    }

    private static void flush(StringBuilder word, List<String> words) {
        if (!word.isEmpty()) {
            words.add(word.toString());
            word.setLength(0);
        }
    }

    /** Edit distance, for suggestions. */
    static int distance(String a, String b) {
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

    /** {@code '; did you mean 'x'?'} for the closest of {@code names}, or nothing. */
    static String suggestion(String name, List<String> names) {
        String lower = name.toLowerCase(Locale.ROOT);
        return names.stream()
                .filter(n -> distance(n.toLowerCase(Locale.ROOT), lower) <= 2)
                .min(java.util.Comparator.comparingInt(n -> distance(n.toLowerCase(Locale.ROOT), lower)))
                .map(n -> "; did you mean '" + n + "'?")
                .orElse("");
    }

    /**
     * The position of column {@code name} in {@code columns}: the exact name, or else the only name
     * that differs in case; -1 when there is none.
     */
    static int find(List<String> columns, String name) {
        int exact = columns.indexOf(name);
        if (exact >= 0) {
            return exact;
        }
        int found = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
            }
        }
        return found;
    }

    /** {@code name} as a quoted identifier of the expression language. */
    static String quote(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    /** {@code type} as the target of {@code CAST}: the logical type, without NOT NULL. */
    static String castType(org.devlive.pipeforge.api.type.DataType type) {
        return type.withNullability(true).toString();
    }

    /** {@code text} as a string literal of the expression language. */
    static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }
}
