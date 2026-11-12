/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.jspecify.annotations.Nullable;

/**
 * How values are printed. Scalars have one text form, used in table cells and as JSON strings:
 * decimals in plain notation, binary values in hex (tables) or Base64 (JSON), dates and times as
 * {@code 2026-11-07 12:30:00.123} with as many fractional digits as the type's precision, and
 * {@code TIMESTAMP_LTZ} in the job time zone with its offset. Arrays, maps and rows are printed as
 * JSON in both formats.
 */
final class Values {

    static final String NULL = "NULL";

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private final ZoneId zone;

    Values(ZoneId zone) {
        this.zone = zone;
    }

    // ------------------------------------------------------------------ text

    /** The text of a value in a table cell; type may be null for columns of unknown tables. */
    String text(@Nullable Object value, @Nullable DataType type) {
        if (value == null) {
            return NULL;
        }
        TypeRoot root = root(value, type);
        return switch (root) {
            case DECIMAL -> ((BigDecimal) value).toPlainString();
            case BYTES -> "0x" + HexFormat.of().formatHex((byte[]) value);
            case DATE, TIME, TIMESTAMP, TIMESTAMP_LTZ -> temporal(value, root, type);
            case ARRAY, MAP, ROW -> json(value, type);
            default -> String.valueOf(value);
        };
    }

    /** Whether a column of this type is right-aligned in tables. */
    static boolean numeric(@Nullable DataType type) {
        return type != null && type.root().isNumeric();
    }

    private String temporal(Object value, TypeRoot root, @Nullable DataType type) {
        int digits = type == null ? needed(value) : type.precision();
        return switch (root) {
            case DATE -> ((LocalDate) value).format(DATE);
            case TIME -> time(digits).format((LocalTime) value);
            case TIMESTAMP -> timestamp(digits, false).format((LocalDateTime) value);
            default -> timestamp(digits, true).format(((Instant) value).atZone(zone));
        };
    }

    /** Fractional digits a value of unknown precision needs: 0, 3, 6 or 9. */
    @SuppressWarnings({"JavaLocalTimeGetNano", "JavaLocalDateTimeGetNano", "JavaInstantGetSecondsGetNano"
    }) // only the fraction matters
    private static int needed(Object value) {
        int nanos =
                switch (value) {
                    case LocalTime time -> time.getNano();
                    case LocalDateTime timestamp -> timestamp.getNano();
                    case Instant instant -> instant.getNano();
                    default -> 0;
                };
        if (nanos == 0) {
            return 0;
        }
        return nanos % 1_000_000 == 0 ? 3 : nanos % 1_000 == 0 ? 6 : 9;
    }

    private static final DateTimeFormatter DATE = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
            .appendPattern("-MM-dd")
            .toFormatter();

    private static final DateTimeFormatter[] TIMES = new DateTimeFormatter[10];
    private static final DateTimeFormatter[] TIMESTAMPS = new DateTimeFormatter[10];
    private static final DateTimeFormatter[] ZONED = new DateTimeFormatter[10];

    static {
        for (int digits = 0; digits <= 9; digits++) {
            TIMES[digits] = clock(new DateTimeFormatterBuilder(), digits).toFormatter();
            TIMESTAMPS[digits] = clock(
                            new DateTimeFormatterBuilder().append(DATE).appendLiteral(' '), digits)
                    .toFormatter();
            ZONED[digits] = clock(new DateTimeFormatterBuilder().append(DATE).appendLiteral(' '), digits)
                    .appendOffset("+HH:MM:ss", "+00:00")
                    .toFormatter();
        }
    }

    private static DateTimeFormatterBuilder clock(DateTimeFormatterBuilder builder, int digits) {
        builder.appendPattern("HH:mm:ss");
        if (digits > 0) {
            builder.appendFraction(ChronoField.NANO_OF_SECOND, digits, digits, true);
        }
        return builder;
    }

    static DateTimeFormatter time(int digits) {
        return TIMES[Math.max(0, Math.min(9, digits))];
    }

    static DateTimeFormatter timestamp(int digits, boolean zoned) {
        int index = Math.max(0, Math.min(9, digits));
        return zoned ? ZONED[index] : TIMESTAMPS[index];
    }

    static DateTimeFormatter date() {
        return DATE;
    }

    // ------------------------------------------------------------------ JSON

    /** The compact JSON text of a value. */
    String json(@Nullable Object value, @Nullable DataType type) {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = JSON.createGenerator(out)) {
            write(generator, value, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /** Opens a generator over {@code out} with the settings of the JSON lines. */
    static JsonGenerator generator(StringWriter out) throws IOException {
        return JSON.createGenerator(out);
    }

    /** Writes a value as JSON. */
    void write(JsonGenerator out, @Nullable Object value, @Nullable DataType type) throws IOException {
        if (value == null) {
            out.writeNull();
            return;
        }
        TypeRoot root = root(value, type);
        switch (root) {
            case BOOLEAN -> out.writeBoolean((Boolean) value);
            case INT8, INT16, INT32 -> out.writeNumber(((Number) value).intValue());
            case INT64 -> out.writeNumber((Long) value);
            case FLOAT32 -> {
                float f = (Float) value;
                if (Float.isFinite(f)) {
                    out.writeNumber(f);
                } else {
                    out.writeString(Float.toString(f));
                }
            }
            case FLOAT64 -> {
                double d = (Double) value;
                if (Double.isFinite(d)) {
                    out.writeNumber(d);
                } else {
                    out.writeString(Double.toString(d));
                }
            }
            case DECIMAL -> out.writeNumber((BigDecimal) value);
            case JSON -> out.writeRawValue((String) value);
            case BYTES -> out.writeBinary((byte[]) value);
            case DATE, TIME, TIMESTAMP, TIMESTAMP_LTZ -> out.writeString(temporal(value, root, type));
            case ARRAY -> {
                out.writeStartArray();
                DataType element = type == null ? null : type.elementType();
                for (Object item : (List<?>) value) {
                    write(out, item, element);
                }
                out.writeEndArray();
            }
            case MAP -> map(out, (Map<?, ?>) value, type);
            case ROW -> row(out, (Row) value, type);
            default -> out.writeString(String.valueOf(value));
        }
    }

    /** Maps with string keys become objects; other maps arrays of [key, value] pairs. */
    private void map(JsonGenerator out, Map<?, ?> map, @Nullable DataType type) throws IOException {
        DataType keyType = type == null ? null : type.keyType();
        DataType valueType = type == null ? null : type.valueType();
        boolean stringKeys = keyType != null
                ? keyType.root() == TypeRoot.STRING
                : map.keySet().stream().allMatch(String.class::isInstance);
        if (stringKeys) {
            out.writeStartObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.writeFieldName(String.valueOf(entry.getKey()));
                write(out, entry.getValue(), valueType);
            }
            out.writeEndObject();
            return;
        }
        out.writeStartArray();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.writeStartArray();
            write(out, entry.getKey(), keyType);
            write(out, entry.getValue(), valueType);
            out.writeEndArray();
        }
        out.writeEndArray();
    }

    /** Nested rows of a known type become objects by field name, others arrays. */
    private void row(JsonGenerator out, Row row, @Nullable DataType type) throws IOException {
        List<DataType.Field> fields = type == null ? null : type.fields();
        if (fields != null && fields.size() == row.arity()) {
            out.writeStartObject();
            for (int i = 0; i < row.arity(); i++) {
                out.writeFieldName(fields.get(i).name());
                write(out, row.get(i), fields.get(i).type());
            }
            out.writeEndObject();
            return;
        }
        out.writeStartArray();
        for (int i = 0; i < row.arity(); i++) {
            write(out, row.get(i), null);
        }
        out.writeEndArray();
    }

    /** The type root of a value: from its declared type, or from its Java class. */
    static TypeRoot root(Object value, @Nullable DataType type) {
        if (type != null && type.root() != TypeRoot.NULL) {
            return type.root();
        }
        for (TypeRoot root : TypeRoot.values()) {
            if (root != TypeRoot.JSON
                    && root != TypeRoot.NULL
                    && root.javaClass().isInstance(value)) {
                return root;
            }
        }
        return TypeRoot.STRING;
    }
}
