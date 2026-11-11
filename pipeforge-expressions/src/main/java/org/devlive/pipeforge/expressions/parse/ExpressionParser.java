/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.parse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * Parses expression text with Calcite's SQL parser: the expression language is the scalar
 * expression syntax of SQL. Identifiers are quoted with backticks, unquoted identifiers keep their
 * case and match case-insensitively, and the lenient conformance accepts {@code !=} and {@code %}.
 *
 * <p>{@code TIMESTAMP_LTZ '...'} literals, which Calcite does not know, are rewritten to a call
 * before parsing, and {@code DAY(x)} (DAY is a keyword) to {@code DAYOFMONTH(x)}; reported
 * positions are mapped back to the original text.
 *
 * <p>The target of {@code CAST} and {@code TRY_CAST} may be written as a logical type, as in job
 * definitions ({@code STRING(20)}, {@code TIMESTAMP_LTZ(3)}, {@code ARRAY<INT32>}, {@code MAP<STRING,
 * INT64>}), which Calcite cannot parse: such a target is read with {@link DataTypes#parse} and
 * replaced by a quoted placeholder of the same length, which {@link Parsed#castType} resolves. Targets
 * that are not logical types, such as {@code VARCHAR(20)}, are left to Calcite.
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

    /**
     * The parsed tree and the rewritten text it refers to.
     *
     * @param castTypes the logical types of {@code CAST} targets, by placeholder name
     */
    public record Parsed(SqlNode node, String text, List<int[]> shifts, Map<String, DataType> castTypes) {
        public Parsed {
            shifts = List.copyOf(shifts);
            castTypes = Map.copyOf(castTypes);
        }

        /** The logical type a {@code CAST} target placeholder stands for, if {@code name} is one. */
        public Optional<DataType> castType(String name) {
            return Optional.ofNullable(castTypes.get(name));
        }

        /** Maps a column of the rewritten text back to the original text. */
        public int originalColumn(int line, int column) {
            int result = column;
            for (int[] shift : shifts) {
                if (shift[0] == line && column > shift[1]) {
                    result -= shift[2];
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
        Map<String, DataType> castTypes = new HashMap<>();
        String rewritten = rewriteCastTypes(rewriteLtzLiterals(text, shifts), castTypes);
        try {
            SqlNode node = SqlParser.create(rewritten, CONFIG).parseExpression();
            return new Parsed(node, rewritten, shifts, castTypes);
        } catch (SqlParseException e) {
            Parsed parsed = new Parsed(null, rewritten, shifts, castTypes);
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
                    shifts.add(new int[] {line, column, 1});
                    i = end;
                    continue;
                }
            }
            if (isDayCall(text, i)) {
                // DAY is a keyword for Calcite; DAY(x) is the same as DAYOFMONTH(x).
                out.append(DAY_FUNCTION);
                column += DAY.length();
                shifts.add(new int[] {line, column, DAY_FUNCTION.length() - DAY.length()});
                i += DAY.length();
                continue;
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

    /** The prefix of {@code CAST} target placeholders; {@code #} cannot start a type name. */
    public static final String CAST_TYPE = "#";

    /**
     * Replaces {@code CAST} / {@code TRY_CAST} targets that are logical types by placeholders of the
     * same length ({@code `#0`} padded with spaces), so that positions do not move. A target that is
     * not a logical type, spans lines, or is shorter than its placeholder stays as it is.
     */
    static String rewriteCastTypes(String text, Map<String, DataType> types) {
        record Frame(boolean cast, int as) {}
        List<int[]> targets = new ArrayList<>();
        Deque<Frame> frames = new ArrayDeque<>();
        boolean castNext = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '`') {
                i = skipQuoted(text, i, c);
                castNext = false;
                continue;
            }
            if (isIdentifierPart(c)) {
                int end = i;
                while (end < text.length() && isIdentifierPart(text.charAt(end))) {
                    end++;
                }
                String word = text.substring(i, end).toUpperCase(Locale.ROOT);
                boolean wordStart = i == 0 || text.charAt(i - 1) != '.';
                Frame top = frames.peek();
                if (word.equals("AS") && top != null && top.cast() && top.as() < 0) {
                    frames.pop();
                    frames.push(new Frame(true, end));
                }
                castNext = wordStart && (word.equals("CAST") || word.equals("TRY_CAST"));
                i = end;
                continue;
            }
            if (c == '(') {
                frames.push(new Frame(castNext, -1));
            } else if (c == ')') {
                Frame frame = frames.poll();
                if (frame != null && frame.cast() && frame.as() >= 0) {
                    targets.add(new int[] {frame.as(), i});
                }
            }
            if (!Character.isWhitespace(c)) {
                castNext = false;
            }
            i++;
        }
        if (targets.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text);
        for (int[] target : targets) {
            String written = text.substring(target[0], target[1]);
            String trimmed = written.strip();
            if (trimmed.isEmpty() || written.indexOf('\n') >= 0) {
                continue;
            }
            DataType type;
            try {
                type = DataTypes.parse(trimmed);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String name = CAST_TYPE + types.size();
            String placeholder = " `" + name + "`";
            if (placeholder.length() > written.length()) {
                continue;
            }
            types.put(name, type);
            out.replace(target[0], target[1], placeholder + " ".repeat(written.length() - placeholder.length()));
        }
        return out.toString();
    }

    private static final String DAY = "DAY";
    private static final String DAY_FUNCTION = "DAYOFMONTH";

    /** {@code DAY} as a whole word followed by an opening parenthesis. */
    private static boolean isDayCall(String text, int i) {
        int end = i + DAY.length();
        if (end > text.length()
                || !text.substring(i, end).equalsIgnoreCase(DAY)
                || (i > 0 && (isIdentifierPart(text.charAt(i - 1)) || text.charAt(i - 1) == '.'))) {
            return false;
        }
        int k = end;
        while (k < text.length() && Character.isWhitespace(text.charAt(k))) {
            k++;
        }
        return k < text.length() && text.charAt(k) == '(';
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
