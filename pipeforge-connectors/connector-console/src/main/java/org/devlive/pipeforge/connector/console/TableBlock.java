/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import java.util.List;

/**
 * Renders rows as an aligned table with a title:
 *
 * <pre>
 * shop.orders
 * +----+----+-------+
 * | op | id | name  |
 * +----+----+-------+
 * | +I |  1 | Ada   |
 * +----+----+-------+
 * </pre>
 *
 * Widths count East Asian wide characters as two columns, so Chinese, Japanese and Korean text
 * stays aligned in terminals.
 */
final class TableBlock {

    private TableBlock() {}

    /**
     * @param headers column names
     * @param rightAligned per column, whether values are right-aligned (numbers)
     * @param rows cell texts, already cut to the maximum width
     */
    static String render(String title, List<String> headers, List<Boolean> rightAligned, List<String[]> rows) {
        int[] widths = new int[headers.size()];
        for (int c = 0; c < widths.length; c++) {
            widths[c] = width(headers.get(c));
        }
        for (String[] row : rows) {
            for (int c = 0; c < widths.length; c++) {
                widths[c] = Math.max(widths[c], width(row[c]));
            }
        }
        StringBuilder rule = new StringBuilder("+");
        for (int width : widths) {
            rule.append("-".repeat(width + 2)).append('+');
        }
        StringBuilder out = new StringBuilder(title).append('\n');
        out.append(rule).append('\n');
        line(out, headers.toArray(String[]::new), widths, null);
        out.append(rule).append('\n');
        for (String[] row : rows) {
            line(out, row, widths, rightAligned);
        }
        out.append(rule).append('\n');
        return out.toString();
    }

    private static void line(StringBuilder out, String[] cells, int[] widths, List<Boolean> rightAligned) {
        out.append('|');
        for (int c = 0; c < widths.length; c++) {
            String pad = " ".repeat(widths[c] - width(cells[c]));
            boolean right = rightAligned != null && rightAligned.get(c);
            out.append(' ')
                    .append(right ? pad : "")
                    .append(cells[c])
                    .append(right ? "" : pad)
                    .append(" |");
        }
        out.append('\n');
    }

    /** The text of a cell: control characters escaped, cut to {@code max} columns (0 for no limit). */
    static String cell(String text, int max) {
        StringBuilder escaped = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            switch (cp) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(cp)) {
                        escaped.append(String.format("\\u%04x", cp));
                    } else {
                        escaped.appendCodePoint(cp);
                    }
                }
            }
        });
        String value = escaped.toString();
        if (max <= 0 || width(value) <= max) {
            return value;
        }
        StringBuilder cut = new StringBuilder();
        int used = 0;
        for (int cp : value.codePoints().toArray()) {
            int w = width(cp);
            if (used + w > max - 1) {
                break;
            }
            cut.appendCodePoint(cp);
            used += w;
        }
        return cut.append('…').toString();
    }

    /** Display width of a text in a terminal. */
    static int width(String text) {
        int width = 0;
        for (int cp : text.codePoints().toArray()) {
            width += width(cp);
        }
        return width;
    }

    private static int width(int cp) {
        if (Character.getType(cp) == Character.NON_SPACING_MARK
                || Character.getType(cp) == Character.ENCLOSING_MARK
                || cp == 0x200B) {
            return 0;
        }
        boolean wide = (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE4F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1F64F)
                || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
        return wide ? 2 : 1;
    }
}
