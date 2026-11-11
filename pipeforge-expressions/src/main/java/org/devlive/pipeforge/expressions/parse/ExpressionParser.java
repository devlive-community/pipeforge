/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * Parses expression text with Calcite's SQL parser: the expression language is the scalar
 * expression syntax of SQL. Identifiers are quoted with backticks, unquoted identifiers keep their
 * case and match case-insensitively, and the lenient conformance accepts {@code !=} and {@code %}.
 *
 * <p>{@code TIMESTAMP_LTZ '...'} literals, which Calcite does not know, are rewritten to a call
 * before parsing; reported positions are mapped back to the original text.
 */
public final class ExpressionParser {

    private static final SqlParser.Config CONFIG = SqlParser.config()
            .withQuoting(Quoting.BACK_TICK)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlConformanceEnum.LENIENT);

    /** Name of the call that {@code TIMESTAMP_LTZ '...'} literals are rewritten to. */
    public static final String LTZ_LITERAL = "TIMESTAMP_LTZ";

    private ExpressionParser() {}

    /** The parsed tree and the rewritten text it refers to. */
    public record Parsed(SqlNode node, String text, List<int[]> shifts) {
        public Parsed {
            shifts = List.copyOf(shifts);
        }

        /** Maps a column of the rewritten text back to the original text. */
        public int originalColumn(int line, int column) {
            int result = column;
            for (int[] shift : shifts) {
                if (shift[0] == line && column > shift[1]) {
                    result--;
                }
            }
            return result;
        }
    }

    /**
     * Parses an expression.
     *
     * @throws PipeForgeException {@link ErrorCode#EXPRESSION_INVALID} with the position of the problem
     */
    public static Parsed parse(String text) {
        List<int[]> shifts = new ArrayList<>();
        String rewritten = rewriteLtzLiterals(text, shifts);
        try {
            SqlNode node = SqlParser.create(rewritten, CONFIG).parseExpression();
            return new Parsed(node, rewritten, shifts);
        } catch (SqlParseException e) {
            Parsed parsed = new Parsed(null, rewritten, shifts);
            int line = e.getPos() == null ? 1 : e.getPos().getLineNum();
            int column = e.getPos() == null
                    ? 1
                    : parsed.originalColumn(line, e.getPos().getColumnNum());
            throw invalid(text, line, column, syntaxReason(e));
        }
    }

    private static String syntaxReason(SqlParseException e) {
        String first = String.valueOf(e.getMessage()).lines().findFirst().orElse("syntax error");
        int at = first.indexOf(" at line ");
        String reason = at > 0 ? first.substring(0, at) : first;
        return reason.replace("Encountered \"<EOF>\"", "unexpected end of expression")
                .replace("Encountered ", "unexpected ")
                .replace("\"", "'")
                .replace(" <EOF>", "");
    }

    /** Builds an {@link ErrorCode#EXPRESSION_INVALID} error. */
    public static PipeForgeException invalid(String expression, int line, int column, String reason) {
        return PipeForgeException.of(ErrorCode.EXPRESSION_INVALID)
                .param("expression", expression)
                .param("line", line)
                .param("column", column)
                .param("reason", reason)
                .build();
    }

    /**
     * Rewrites {@code TIMESTAMP_LTZ '...'} to {@code TIMESTAMP_LTZ('...')}: the whitespace before the
     * quote becomes the opening parenthesis and a closing one is inserted after the string.
     */
    static String rewriteLtzLiterals(String text, List<int[]> shifts) {
        StringBuilder out = new StringBuilder(text.length() + 4);
        int line = 1;
        int column = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '`') {
                int end = skipQuoted(text, i, c);
                String quoted = text.substring(i, end);
                out.append(quoted);
                for (int qi = 0; qi < quoted.length(); qi++) {
                    char q = quoted.charAt(qi);
                    if (q == '\n') {
                        line++;
                        column = 1;
                    } else {
                        column++;
                    }
                }
                i = end;
                continue;
            }
            if (matchesKeyword(text, i)) {
                int j = i + LTZ_LITERAL.length();
                int k = j;
                while (k < text.length() && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
                    k++;
                }
                if (k > j && k < text.length() && text.charAt(k) == '\'') {
                    int end = skipQuoted(text, k, '\'');
                    out.append(text, i, k - 1).append('(').append(text, k, end).append(')');
                    column += end - i;
                    shifts.add(new int[] {line, column});
                    i = end;
                    continue;
                }
            }
            out.append(c);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            i++;
        }
        return out.toString();
    }

    private static boolean matchesKeyword(String text, int i) {
        int end = i + LTZ_LITERAL.length();
        return end <= text.length()
                && text.substring(i, end).toUpperCase(Locale.ROOT).equals(LTZ_LITERAL)
                && (i == 0 || !isIdentifierPart(text.charAt(i - 1)))
                && (end == text.length() || !isIdentifierPart(text.charAt(end)));
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Index after a quoted section starting at {@code start}; doubled quotes are escapes. */
    private static int skipQuoted(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }
}
