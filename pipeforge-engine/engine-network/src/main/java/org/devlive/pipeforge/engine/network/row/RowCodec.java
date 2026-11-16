/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.row;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.RowKind;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * The row serialization format of protocol v1 (protocol specification, section 4). A row is
 * written as its length (varint), a header byte with the row kind and whether it has an event time,
 * the number of its table (varint), a null bitmap, eight bytes per column and a variable section
 * for the values that do not fit in eight bytes. Offsets are relative to the header of the row, or
 * to the start of a nested value.
 *
 * <p>An instance keeps a buffer for encoding and is not thread-safe; use one per channel.
 */
public final class RowCodec {

    /** A decoded row and its event time in milliseconds, if it had one. */
    public record Decoded(Row row, @Nullable Long eventTime) {}

    static final int EVENT_TIME = 0b100;
    static final long INLINE = 0x80L << 56;
    static final int MAX_INLINE = 7;
    static final int MAX_COMPACT_PRECISION = 18;
    static final int MAX_MICRO_PRECISION = 6;

    private final Map<Integer, TableLayout> byId = new HashMap<>();
    private final Map<TableId, TableLayout> byTable = new HashMap<>();
    private final RowBuffer scratch = new RowBuffer(256);

    public RowCodec(Collection<TableLayout> tables) {
        for (TableLayout table : tables) {
            if (byId.putIfAbsent(table.id(), table) != null) {
                throw new IllegalArgumentException("two tables have the number " + table.id());
            }
            if (byTable.putIfAbsent(table.table(), table) != null) {
                throw new IllegalArgumentException("table " + table.table() + " is in the directory twice");
            }
        }
    }

    // ---------------------------------------------------------------------------------- encode

    /**
     * Appends {@code row} to {@code out}.
     *
     * @param eventTime the event time of the row in milliseconds, or {@code null}
     * @throws PipeForgeException {@link ErrorCode#TYPE_CONVERSION} when a value is not of its
     *     column's type, {@link ErrorCode#VALUE_OUT_OF_RANGE} when it does not fit the type
     */
    public void encode(Row row, @Nullable Long eventTime, RowBuffer out) {
        TableLayout table = byTable.get(row.table());
        if (table == null) {
            throw internal("a row of table " + row.table() + ", which is not in the table directory");
        }
        List<DataType.Field> columns = table.columns();
        if (row.arity() != columns.size()) {
            throw internal("a row of " + row.arity() + " values for table " + row.table() + " with " + columns.size()
                    + " columns");
        }
        scratch.clear();
        scratch.writeByte(kind(row.kind()) | (eventTime == null ? 0 : EVENT_TIME));
        scratch.writeVarint(table.id());
        writeSlots(
                scratch,
                0,
                columns.size(),
                i -> columns.get(i).type(),
                row::get,
                i -> columns.get(i).name(),
                eventTime);
        out.writeVarint(scratch.size());
        out.writeBuffer(scratch);
    }

    /**
     * Writes a null bitmap, the eight-byte slots and the variable section of {@code count} values.
     * Offsets in the slots are relative to {@code base}.
     */
    private static void writeSlots(
            RowBuffer buf,
            int base,
            int count,
            IntFunction<DataType> types,
            IntFunction<@Nullable Object> values,
            IntFunction<String> names,
            @Nullable Long eventTime) {
        int bitmap = buf.size();
        buf.skip((count + 7) / 8);
        int fixed = buf.size();
        buf.skip(8 * count);
        if (eventTime != null) {
            buf.writeLong(eventTime);
        }
        for (int i = 0; i < count; i++) {
            Object value = values.apply(i);
            DataType type = types.apply(i);
            if (value == null) {
                buf.orByte(bitmap + i / 8, 1 << (i % 8));
                continue;
            }
            buf.setLong(fixed + 8 * i, writeValue(buf, base, type, value, names.apply(i)));
        }
    }

    /** The eight bytes of a value; a value that does not fit is appended and referenced. */
    // toEpochSecond gives the seconds of a LocalDateTime, getNano the rest
    @SuppressWarnings("JavaLocalDateTimeGetNano")
    private static long writeValue(RowBuffer buf, int base, DataType type, Object value, String column) {
        return switch (type.root()) {
            case BOOLEAN -> as(value, Boolean.class, type, column) ? 1 : 0;
            case INT8 -> as(value, Byte.class, type, column);
            case INT16 -> as(value, Short.class, type, column);
            case INT32 -> as(value, Integer.class, type, column);
            case INT64 -> as(value, Long.class, type, column);
            case FLOAT32 -> Float.floatToRawIntBits(as(value, Float.class, type, column)) & 0xFFFF_FFFFL;
            case FLOAT64 -> Double.doubleToRawLongBits(as(value, Double.class, type, column));
            case DATE -> as(value, LocalDate.class, type, column).toEpochDay();
            case TIME -> as(value, LocalTime.class, type, column).toNanoOfDay();
            case TIMESTAMP -> {
                LocalDateTime time = as(value, LocalDateTime.class, type, column);
                yield timestamp(buf, base, time.toEpochSecond(ZoneOffset.UTC), time.getNano(), type, column);
            }
            case TIMESTAMP_LTZ -> {
                Instant time = as(value, Instant.class, type, column);
                yield timestamp(buf, base, time.getEpochSecond(), time.getNano(), type, column);
            }
            case DECIMAL -> decimal(buf, base, as(value, BigDecimal.class, type, column), type, column);
            case STRING, JSON -> bytes(
                    buf, base, as(value, String.class, type, column).getBytes(StandardCharsets.UTF_8));
            case BYTES -> bytes(buf, base, as(value, byte[].class, type, column));
            case ARRAY -> {
                int start = buf.size();
                writeArray(buf, type.elementType(), as(value, List.class, type, column), column + "[]");
                yield ref(start - base, buf.size() - start);
            }
            case MAP -> {
                int start = buf.size();
                Map<?, ?> map = as(value, Map.class, type, column);
                writeArray(buf, type.keyType(), new ArrayList<>(map.keySet()), column + ".key");
                writeArray(buf, type.valueType(), new ArrayList<>(map.values()), column + ".value");
                yield ref(start - base, buf.size() - start);
            }
            case ROW -> {
                Row nested = as(value, Row.class, type, column);
                List<DataType.Field> fields = type.fields();
                if (nested.arity() != fields.size()) {
                    throw typeConversion(column, type, "a row of " + nested.arity() + " values");
                }
                int start = buf.size();
                writeSlots(
                        buf,
                        start,
                        fields.size(),
                        i -> fields.get(i).type(),
                        nested::get,
                        i -> column + "." + fields.get(i).name(),
                        null);
                yield ref(start - base, buf.size() - start);
            }
            case NULL -> throw typeConversion(
                    column, type, "a value of " + value.getClass().getName());
        };
    }

    private static void writeArray(RowBuffer buf, DataType element, List<?> list, String column) {
        int start = buf.size();
        buf.writeVarint(list.size());
        writeSlots(buf, start, list.size(), i -> element, list::get, i -> column, null);
    }

    /** Microseconds in the slot (p ≤ 6), or microseconds and the nanoseconds within them. */
    private static long timestamp(RowBuffer buf, int base, long seconds, int nanos, DataType type, String column) {
        long micros;
        try {
            micros = Math.addExact(Math.multiplyExact(seconds, 1_000_000L), nanos / 1000);
        } catch (ArithmeticException e) {
            throw outOfRange(column, type, "more microseconds from 1970 than fit in 64 bits");
        }
        if (type.precision() <= MAX_MICRO_PRECISION) {
            return micros;
        }
        int start = buf.size();
        buf.writeLong(micros);
        buf.writeShort(nanos % 1000);
        return ref(start - base, 10);
    }

    private static long decimal(RowBuffer buf, int base, BigDecimal value, DataType type, String column) {
        BigInteger unscaled;
        try {
            unscaled = value.setScale(type.scale(), RoundingMode.UNNECESSARY).unscaledValue();
        } catch (ArithmeticException e) {
            throw outOfRange(column, type, value.toPlainString() + " has more than " + type.scale() + " decimals");
        }
        if (unscaled.abs().compareTo(BigInteger.TEN.pow(type.precision())) >= 0) {
            int digits = unscaled.abs().toString().length();
            throw outOfRange(
                    column,
                    type,
                    value.toPlainString() + " needs " + digits + " digits with " + type.scale()
                            + " decimals, more than " + type.precision());
        }
        if (type.precision() <= MAX_COMPACT_PRECISION) {
            return unscaled.longValueExact();
        }
        return bytes(buf, base, unscaled.toByteArray(), false);
    }

    private static long bytes(RowBuffer buf, int base, byte[] bytes) {
        return bytes(buf, base, bytes, true);
    }

    /** Up to seven bytes inline when allowed, otherwise in the variable section. */
    private static long bytes(RowBuffer buf, int base, byte[] bytes, boolean inline) {
        if (inline && bytes.length <= MAX_INLINE) {
            long word = INLINE | ((long) bytes.length << 56);
            for (int i = 0; i < bytes.length; i++) {
                word |= (bytes[i] & 0xFFL) << (8 * (6 - i));
            }
            return word;
        }
        int start = buf.size();
        buf.writeBytes(bytes);
        return ref(start - base, bytes.length);
    }

    /** Offset in the high 32 bits, length in the low 32 bits; the highest bit stays 0. */
    static long ref(int offset, int length) {
        return ((long) offset << 32) | (length & 0xFFFF_FFFFL);
    }

    private static <T> T as(Object value, Class<T> type, DataType column, String name) {
        if (!type.isInstance(value)) {
            throw typeConversion(name, column, "a value of " + value.getClass().getName());
        }
        return type.cast(value);
    }

    private static int kind(RowKind kind) {
        return switch (kind) {
            case INSERT -> 0;
            case UPDATE_BEFORE -> 1;
            case UPDATE_AFTER -> 2;
            case DELETE -> 3;
        };
    }

    // ---------------------------------------------------------------------------------- decode

    /**
     * Reads the row at the position of {@code in} and moves past it.
     *
     * @throws PipeForgeException {@link ErrorCode#INTERNAL} when the bytes are not a row of a known
     *     table
     */
    public Decoded decode(ByteBuffer in) {
        try {
            int length = Varint.readInt(in);
            ByteBuffer row = in.slice(in.position(), length);
            in.position(in.position() + length);
            int header = row.get() & 0xFF;
            if ((header & ~(EVENT_TIME | 0b11)) != 0) {
                throw new IllegalArgumentException("unknown bits in row header " + Integer.toBinaryString(header));
            }
            int id = Varint.readInt(row);
            TableLayout table = byId.get(id);
            if (table == null) {
                throw new IllegalArgumentException("table " + id + " is not in the table directory");
            }
            List<DataType.Field> columns = table.columns();
            boolean hasTime = (header & EVENT_TIME) != 0;
            Slots slots = new Slots(row, 0, row.position(), columns.size());
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = slots.read(i, columns.get(i).type(), table.table());
            }
            Long eventTime = hasTime ? row.getLong(slots.end) : null;
            return new Decoded(GenericRow.ofKind(table.table(), kind(header & 0b11), values), eventTime);
        } catch (IllegalArgumentException
                | IndexOutOfBoundsException
                | BufferUnderflowException
                | ArithmeticException
                | DateTimeException e) {
            throw internal("a malformed row in a data buffer: " + e.getMessage(), e);
        }
    }

    /** Reads all rows from the position of {@code in} to its limit. */
    public List<Decoded> decodeAll(ByteBuffer in) {
        List<Decoded> rows = new ArrayList<>();
        while (in.hasRemaining()) {
            rows.add(decode(in));
        }
        return rows;
    }

    /** The bitmap and slots of {@code count} values starting at {@code at}; offsets from {@code base}. */
    private static final class Slots {
        final ByteBuffer buf;
        final int base;
        final int bitmap;
        final int fixed;
        /** Where the slots end: the variable section starts here. */
        final int end;
        /** The end of the furthest value referenced so far. */
        int extent;

        Slots(ByteBuffer buf, int base, int at, int count) {
            this.buf = buf;
            this.base = base;
            this.bitmap = at;
            this.fixed = at + (count + 7) / 8;
            this.end = fixed + 8 * count;
            if (end > buf.limit()) {
                throw new IllegalArgumentException("the slots of " + count + " values end after the row");
            }
            this.extent = end;
        }

        boolean isNull(int i) {
            return (buf.get(bitmap + i / 8) & (1 << (i % 8))) != 0;
        }

        @Nullable
        Object read(int i, DataType type, TableId table) {
            if (isNull(i)) {
                return null;
            }
            long word = buf.getLong(fixed + 8 * i);
            return switch (type.root()) {
                case BOOLEAN -> word != 0;
                case INT8 -> (byte) word;
                case INT16 -> (short) word;
                case INT32 -> (int) word;
                case INT64 -> word;
                case FLOAT32 -> Float.intBitsToFloat((int) word);
                case FLOAT64 -> Double.longBitsToDouble(word);
                case DATE -> LocalDate.ofEpochDay(word);
                case TIME -> LocalTime.ofNanoOfDay(word);
                case TIMESTAMP -> {
                    long[] time = timestamp(word, type);
                    yield LocalDateTime.ofEpochSecond(time[0], (int) time[1], ZoneOffset.UTC);
                }
                case TIMESTAMP_LTZ -> {
                    long[] time = timestamp(word, type);
                    yield Instant.ofEpochSecond(time[0], time[1]);
                }
                case DECIMAL -> type.precision() <= MAX_COMPACT_PRECISION
                        ? BigDecimal.valueOf(word, type.scale())
                        : new BigDecimal(new BigInteger(bytes(word, false)), type.scale());
                case STRING, JSON -> new String(bytes(word, true), StandardCharsets.UTF_8);
                case BYTES -> bytes(word, true);
                case ARRAY -> {
                    int at = at(word);
                    yield readArray(at, type.elementType(), table).values;
                }
                case MAP -> {
                    int at = at(word);
                    Array keys = readArray(at, type.keyType(), table);
                    Array values = readArray(keys.end, type.valueType(), table);
                    if (keys.values.size() != values.values.size()) {
                        throw new IllegalArgumentException(
                                "a map with " + keys.values.size() + " keys and " + values.values.size() + " values");
                    }
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (int k = 0; k < keys.values.size(); k++) {
                        map.put(keys.values.get(k), values.values.get(k));
                    }
                    yield map;
                }
                case ROW -> {
                    int at = at(word);
                    List<DataType.Field> fields = type.fields();
                    Slots nested = new Slots(buf, at, at, fields.size());
                    Object[] values = new Object[fields.size()];
                    for (int f = 0; f < values.length; f++) {
                        values[f] = nested.read(f, fields.get(f).type(), table);
                    }
                    yield GenericRow.of(table, values);
                }
                case NULL -> throw new IllegalArgumentException("a value in a column of type NULL");
            };
        }

        /** Seconds and nanoseconds of a timestamp slot. */
        long[] timestamp(long word, DataType type) {
            long micros;
            int subMicros = 0;
            if (type.precision() <= MAX_MICRO_PRECISION) {
                micros = word;
            } else {
                int at = at(word);
                if (length(word) != 10) {
                    throw new IllegalArgumentException("a timestamp of " + length(word) + " bytes");
                }
                micros = buf.getLong(at);
                subMicros = buf.getShort(at + 8);
                if (subMicros < 0 || subMicros > 999) {
                    throw new IllegalArgumentException("a timestamp with " + subMicros + " nanoseconds within a µs");
                }
            }
            return new long[] {Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000 + subMicros};
        }

        byte[] bytes(long word, boolean inline) {
            if (word < 0) {
                if (!inline) {
                    throw new IllegalArgumentException("an inline value where none is allowed");
                }
                int length = (int) ((word >>> 56) & 0x7F);
                if (length > MAX_INLINE) {
                    throw new IllegalArgumentException("an inline value of " + length + " bytes");
                }
                byte[] bytes = new byte[length];
                for (int i = 0; i < length; i++) {
                    bytes[i] = (byte) (word >>> (8 * (6 - i)));
                }
                return bytes;
            }
            int at = at(word);
            byte[] bytes = new byte[length(word)];
            buf.get(at, bytes);
            return bytes;
        }

        /** The absolute position of a referenced value, checked to lie in the row. */
        int at(long word) {
            if (word < 0) {
                throw new IllegalArgumentException("an inline value where a reference is expected");
            }
            // the highest bit is 0, so the offset is below 2^31 and the sums cannot overflow a long
            long at = base + (word >>> 32);
            long end = at + length(word);
            if (end > buf.limit()) {
                throw new IllegalArgumentException(
                        "a value at " + at + " of " + length(word) + " bytes outside the row of " + buf.limit());
            }
            extent = (int) Math.max(extent, end);
            return (int) at;
        }

        static int length(long word) {
            long length = word & 0xFFFF_FFFFL;
            if (length > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("a value of " + length + " bytes");
            }
            return (int) length;
        }

        Array readArray(int at, DataType element, TableId table) {
            ByteBuffer cursor = buf.duplicate().position(at);
            int count = Varint.readInt(cursor);
            if (count > buf.limit()) {
                throw new IllegalArgumentException("an array of " + count + " elements");
            }
            Slots slots = new Slots(buf, at, cursor.position(), count);
            List<@Nullable Object> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(slots.read(i, element, table));
            }
            return new Array(values, slots.extent);
        }
    }

    private record Array(List<@Nullable Object> values, int end) {}

    private static RowKind kind(int bits) {
        return switch (bits) {
            case 0 -> RowKind.INSERT;
            case 1 -> RowKind.UPDATE_BEFORE;
            case 2 -> RowKind.UPDATE_AFTER;
            default -> RowKind.DELETE;
        };
    }

    // ---------------------------------------------------------------------------------- errors

    private static PipeForgeException typeConversion(String column, DataType type, String reason) {
        return PipeForgeException.of(ErrorCode.TYPE_CONVERSION)
                .param("column", column)
                .param("type", type.toString())
                .param("reason", reason)
                .build();
    }

    private static PipeForgeException outOfRange(String column, DataType type, String reason) {
        return PipeForgeException.of(ErrorCode.VALUE_OUT_OF_RANGE)
                .param("column", column)
                .param("type", type.toString())
                .param("reason", reason)
                .build();
    }

    private static PipeForgeException internal(String detail) {
        return internal(detail, null);
    }

    private static PipeForgeException internal(String detail, @Nullable Throwable cause) {
        return PipeForgeException.of(ErrorCode.INTERNAL)
                .param("detail", detail)
                .cause(cause)
                .build();
    }
}
