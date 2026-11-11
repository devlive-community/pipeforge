/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.annotation.Stable;
import org.jspecify.annotations.Nullable;

/**
 * Factory and parser for {@link DataType}. Types are nullable unless {@link DataType#notNull()}
 * is applied.
 *
 * <p>The parser accepts the canonical names and common SQL aliases, case-insensitively: {@code
 * INT}/{@code INTEGER} for INT32, {@code BIGINT} for INT64, {@code SMALLINT}, {@code TINYINT},
 * {@code FLOAT}, {@code DOUBLE}, {@code VARCHAR(n)}, {@code CHAR(n)}, {@code TEXT}, {@code
 * VARBINARY(n)}, {@code BINARY(n)}, {@code BOOL}, {@code NUMERIC(p, s)}, {@code DATETIME(p)}.
 */
@Stable
public final class DataTypes {

    /** Highest decimal precision. */
    public static final int MAX_DECIMAL_PRECISION = 38;

    /** Highest fractional-second precision of TIME and timestamps (nanoseconds). */
    public static final int MAX_TIME_PRECISION = 9;

    /** Default precision of TIMESTAMP and TIMESTAMP_LTZ (microseconds). */
    public static final int DEFAULT_TIMESTAMP_PRECISION = 6;

    private static final Map<String, TypeRoot> ALIASES = Map.ofEntries(
            Map.entry("BOOLEAN", TypeRoot.BOOLEAN),
            Map.entry("BOOL", TypeRoot.BOOLEAN),
            Map.entry("INT8", TypeRoot.INT8),
            Map.entry("TINYINT", TypeRoot.INT8),
            Map.entry("INT16", TypeRoot.INT16),
            Map.entry("SMALLINT", TypeRoot.INT16),
            Map.entry("INT32", TypeRoot.INT32),
            Map.entry("INT", TypeRoot.INT32),
            Map.entry("INTEGER", TypeRoot.INT32),
            Map.entry("INT64", TypeRoot.INT64),
            Map.entry("BIGINT", TypeRoot.INT64),
            Map.entry("FLOAT32", TypeRoot.FLOAT32),
            Map.entry("FLOAT", TypeRoot.FLOAT32),
            Map.entry("REAL", TypeRoot.FLOAT32),
            Map.entry("FLOAT64", TypeRoot.FLOAT64),
            Map.entry("DOUBLE", TypeRoot.FLOAT64),
            Map.entry("DECIMAL", TypeRoot.DECIMAL),
            Map.entry("NUMERIC", TypeRoot.DECIMAL),
            Map.entry("STRING", TypeRoot.STRING),
            Map.entry("VARCHAR", TypeRoot.STRING),
            Map.entry("CHAR", TypeRoot.STRING),
            Map.entry("TEXT", TypeRoot.STRING),
            Map.entry("BYTES", TypeRoot.BYTES),
            Map.entry("VARBINARY", TypeRoot.BYTES),
            Map.entry("BINARY", TypeRoot.BYTES),
            Map.entry("DATE", TypeRoot.DATE),
            Map.entry("TIME", TypeRoot.TIME),
            Map.entry("TIMESTAMP", TypeRoot.TIMESTAMP),
            Map.entry("DATETIME", TypeRoot.TIMESTAMP),
            Map.entry("TIMESTAMP_LTZ", TypeRoot.TIMESTAMP_LTZ),
            Map.entry("ARRAY", TypeRoot.ARRAY),
            Map.entry("MAP", TypeRoot.MAP),
            Map.entry("ROW", TypeRoot.ROW),
            Map.entry("JSON", TypeRoot.JSON),
            Map.entry("NULL", TypeRoot.NULL));

    private DataTypes() {}

    private static DataType simple(TypeRoot root) {
        return new DataType(root, true, null, 0, 0, List.of(), List.of());
    }

    public static DataType BOOLEAN() {
        return simple(TypeRoot.BOOLEAN);
    }

    public static DataType INT8() {
        return simple(TypeRoot.INT8);
    }

    public static DataType INT16() {
        return simple(TypeRoot.INT16);
    }

    public static DataType INT32() {
        return simple(TypeRoot.INT32);
    }

    public static DataType INT64() {
        return simple(TypeRoot.INT64);
    }

    public static DataType FLOAT32() {
        return simple(TypeRoot.FLOAT32);
    }

    public static DataType FLOAT64() {
        return simple(TypeRoot.FLOAT64);
    }

    public static DataType DECIMAL(int precision, int scale) {
        if (precision < 1 || precision > MAX_DECIMAL_PRECISION) {
            throw new IllegalArgumentException("DECIMAL precision must be between 1 and 38, got " + precision);
        }
        if (scale < 0 || scale > precision) {
            throw new IllegalArgumentException("DECIMAL scale must be between 0 and the precision, got " + scale);
        }
        return new DataType(TypeRoot.DECIMAL, true, null, precision, scale, List.of(), List.of());
    }

    /** An unbounded string. */
    public static DataType STRING() {
        return simple(TypeRoot.STRING);
    }

    /** A string of at most {@code length} characters. */
    public static DataType STRING(int length) {
        return bounded(TypeRoot.STRING, length);
    }

    public static DataType BYTES() {
        return simple(TypeRoot.BYTES);
    }

    public static DataType BYTES(int length) {
        return bounded(TypeRoot.BYTES, length);
    }

    private static DataType bounded(TypeRoot root, int length) {
        if (length < 1) {
            throw new IllegalArgumentException(root + " length must be positive, got " + length);
        }
        return new DataType(root, true, length, 0, 0, List.of(), List.of());
    }

    public static DataType DATE() {
        return simple(TypeRoot.DATE);
    }

    public static DataType TIME() {
        return TIME(0);
    }

    public static DataType TIME(int precision) {
        return temporal(TypeRoot.TIME, precision);
    }

    public static DataType TIMESTAMP() {
        return TIMESTAMP(DEFAULT_TIMESTAMP_PRECISION);
    }

    public static DataType TIMESTAMP(int precision) {
        return temporal(TypeRoot.TIMESTAMP, precision);
    }

    public static DataType TIMESTAMP_LTZ() {
        return TIMESTAMP_LTZ(DEFAULT_TIMESTAMP_PRECISION);
    }

    public static DataType TIMESTAMP_LTZ(int precision) {
        return temporal(TypeRoot.TIMESTAMP_LTZ, precision);
    }

    private static DataType temporal(TypeRoot root, int precision) {
        if (precision < 0 || precision > MAX_TIME_PRECISION) {
            throw new IllegalArgumentException(root + " precision must be between 0 and 9, got " + precision);
        }
        return new DataType(root, true, null, precision, 0, List.of(), List.of());
    }

    public static DataType ARRAY(DataType element) {
        return new DataType(TypeRoot.ARRAY, true, null, 0, 0, List.of(element), List.of());
    }

    public static DataType MAP(DataType key, DataType value) {
        return new DataType(TypeRoot.MAP, true, null, 0, 0, List.of(key, value), List.of());
    }

    public static DataType ROW(DataType.Field... fields) {
        return ROW(List.of(fields));
    }

    public static DataType ROW(List<DataType.Field> fields) {
        Set<String> names = new HashSet<>();
        for (DataType.Field field : fields) {
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("Duplicate ROW field: " + field.name());
            }
        }
        return new DataType(TypeRoot.ROW, true, null, 0, 0, List.of(), fields);
    }

    public static DataType.Field FIELD(String name, DataType type) {
        return new DataType.Field(name, type);
    }

    public static DataType JSON() {
        return simple(TypeRoot.JSON);
    }

    public static DataType NULL() {
        return simple(TypeRoot.NULL);
    }

    /**
     * Parses a type, e.g. {@code decimal(10,2) not null} or {@code MAP<STRING, ARRAY<INT>>}.
     *
     * @throws IllegalArgumentException with the position of the problem if the text is not a type
     */
    public static DataType parse(String text) {
        Parser parser = new Parser(text);
        DataType type = parser.type();
        parser.skipSpaces();
        if (!parser.atEnd()) {
            throw parser.error("unexpected '" + parser.rest() + "'");
        }
        return type;
    }

    static String quoteIfNeeded(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") ? name : "`" + name.replace("`", "``") + "`";
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        DataType type() {
            skipSpaces();
            int start = pos;
            String word = identifier();
            TypeRoot root = ALIASES.get(word.toUpperCase(Locale.ROOT));
            if (root == null) {
                pos = start;
                throw error("unknown type '" + word + "'");
            }
            DataType type =
                    switch (root) {
                        case DECIMAL -> decimal();
                        case STRING, BYTES -> {
                            @Nullable Integer length = optionalNumber();
                            yield length == null ? simple(root) : bounded(root, length);
                        }
                        case TIME -> {
                            @Nullable Integer precision = optionalNumber();
                            yield TIME(precision == null ? 0 : precision);
                        }
                        case TIMESTAMP -> {
                            @Nullable Integer precision = optionalNumber();
                            yield TIMESTAMP(precision == null ? DEFAULT_TIMESTAMP_PRECISION : precision);
                        }
                        case TIMESTAMP_LTZ -> {
                            @Nullable Integer precision = optionalNumber();
                            yield TIMESTAMP_LTZ(precision == null ? DEFAULT_TIMESTAMP_PRECISION : precision);
                        }
                        case ARRAY -> {
                            expect('<');
                            DataType element = type();
                            expect('>');
                            yield ARRAY(element);
                        }
                        case MAP -> {
                            expect('<');
                            DataType key = type();
                            expect(',');
                            DataType value = type();
                            expect('>');
                            yield MAP(key, value);
                        }
                        case ROW -> row();
                        default -> simple(root);
                    };
            return notNullSuffix() ? type.notNull() : type;
        }

        private DataType decimal() {
            skipSpaces();
            if (!peek('(')) {
                return DECIMAL(10, 0);
            }
            expect('(');
            int precision = number();
            int scale = 0;
            skipSpaces();
            if (peek(',')) {
                expect(',');
                scale = number();
            }
            expect(')');
            try {
                return DECIMAL(precision, scale);
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
        }

        private DataType row() {
            expect('<');
            List<DataType.Field> fields = new ArrayList<>();
            skipSpaces();
            if (!peek('>')) {
                do {
                    skipSpaces();
                    String name = fieldName();
                    fields.add(FIELD(name, type()));
                    skipSpaces();
                } while (consume(','));
            }
            expect('>');
            try {
                return ROW(fields);
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
        }

        private String fieldName() {
            if (peek('`')) {
                pos++;
                StringBuilder name = new StringBuilder();
                while (!atEnd()) {
                    char c = text.charAt(pos++);
                    if (c == '`') {
                        if (peek('`')) {
                            pos++;
                            name.append('`');
                            continue;
                        }
                        return name.toString();
                    }
                    name.append(c);
                }
                throw error("unterminated quoted field name");
            }
            return identifier();
        }

        private boolean notNullSuffix() {
            int start = pos;
            skipSpaces();
            if (matchWord("NOT")) {
                skipSpaces();
                if (matchWord("NULL")) {
                    return true;
                }
                throw error("expected NULL after NOT");
            }
            if (matchWord("NULL")) {
                return false;
            }
            pos = start;
            return false;
        }

        private boolean matchWord(String word) {
            int end = pos + word.length();
            if (end <= text.length()
                    && text.substring(pos, end).equalsIgnoreCase(word)
                    && (end == text.length() || !Character.isLetterOrDigit(text.charAt(end)))) {
                pos = end;
                return true;
            }
            return false;
        }

        private @Nullable Integer optionalNumber() {
            skipSpaces();
            if (!peek('(')) {
                return null;
            }
            expect('(');
            int value = number();
            expect(')');
            return value;
        }

        private String identifier() {
            skipSpaces();
            int start = pos;
            while (!atEnd() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            if (start == pos) {
                throw error(atEnd() ? "unexpected end of type" : "expected a name");
            }
            return text.substring(start, pos);
        }

        private int number() {
            skipSpaces();
            int start = pos;
            while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw error("expected a number");
            }
            try {
                return Integer.parseInt(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("number too large");
            }
        }

        private void expect(char c) {
            skipSpaces();
            if (!consume(c)) {
                throw error("expected '" + c + "'");
            }
        }

        private boolean consume(char c) {
            skipSpaces();
            if (peek(c)) {
                pos++;
                return true;
            }
            return false;
        }

        private boolean peek(char c) {
            return !atEnd() && text.charAt(pos) == c;
        }

        void skipSpaces() {
            while (!atEnd() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        String rest() {
            return text.substring(pos);
        }

        IllegalArgumentException error(@Nullable String message) {
            return new IllegalArgumentException(
                    "Invalid type '" + text + "' at position " + (pos + 1) + ": " + message);
        }
    }
}
