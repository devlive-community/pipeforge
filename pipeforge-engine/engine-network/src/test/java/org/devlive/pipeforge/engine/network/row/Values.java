/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.row;

import static org.devlive.pipeforge.api.type.DataTypes.ARRAY;
import static org.devlive.pipeforge.api.type.DataTypes.BOOLEAN;
import static org.devlive.pipeforge.api.type.DataTypes.BYTES;
import static org.devlive.pipeforge.api.type.DataTypes.DATE;
import static org.devlive.pipeforge.api.type.DataTypes.DECIMAL;
import static org.devlive.pipeforge.api.type.DataTypes.FIELD;
import static org.devlive.pipeforge.api.type.DataTypes.FLOAT32;
import static org.devlive.pipeforge.api.type.DataTypes.FLOAT64;
import static org.devlive.pipeforge.api.type.DataTypes.INT16;
import static org.devlive.pipeforge.api.type.DataTypes.INT32;
import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.INT8;
import static org.devlive.pipeforge.api.type.DataTypes.JSON;
import static org.devlive.pipeforge.api.type.DataTypes.MAP;
import static org.devlive.pipeforge.api.type.DataTypes.ROW;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;
import static org.devlive.pipeforge.api.type.DataTypes.TIME;
import static org.devlive.pipeforge.api.type.DataTypes.TIMESTAMP;
import static org.devlive.pipeforge.api.type.DataTypes.TIMESTAMP_LTZ;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.jspecify.annotations.Nullable;

/** Types and values for the row format tests: boundaries, random types and random values. */
final class Values {

    static final TableId TABLE = TableId.of("shop", "orders");

    static final LocalDateTime FIRST = LocalDateTime.of(1, 1, 1, 0, 0);
    static final LocalDateTime LAST = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999);

    private Values() {}

    /** One type per family and the precisions that change the encoding. */
    static List<DataType> types() {
        return List.of(
                BOOLEAN(),
                INT8(),
                INT16(),
                INT32(),
                INT64(),
                FLOAT32(),
                FLOAT64(),
                DECIMAL(10, 2),
                DECIMAL(18, 0),
                DECIMAL(19, 0),
                DECIMAL(38, 10),
                STRING(),
                JSON(),
                BYTES(),
                DATE(),
                TIME(3),
                TIME(9),
                TIMESTAMP(3),
                TIMESTAMP(6),
                TIMESTAMP(9),
                TIMESTAMP_LTZ(6),
                TIMESTAMP_LTZ(9),
                ARRAY(INT32()),
                ARRAY(STRING()),
                ARRAY(ARRAY(INT64())),
                MAP(STRING(), INT64()),
                MAP(INT32(), ARRAY(BYTES())),
                ROW(FIELD("a", INT32()), FIELD("b", STRING())),
                ROW(FIELD("id", INT64()), FIELD("tags", ARRAY(STRING())), FIELD("price", DECIMAL(38, 4))),
                DataTypes.NULL());
    }

    /** The edge values of a type; the type's scale and precision are respected. */
    static List<@Nullable Object> boundaries(DataType type) {
        return boundaries(type, true);
    }

    /** @param large whether to include a string of 70,000 characters and 100,000 bytes */
    static List<@Nullable Object> boundaries(DataType type, boolean large) {
        List<@Nullable Object> values = new ArrayList<>();
        values.add(null);
        switch (type.root()) {
            case BOOLEAN -> values.addAll(List.of(true, false));
            case INT8 -> values.addAll(List.of(Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) 0, (byte) -1));
            case INT16 -> values.addAll(List.of(Short.MIN_VALUE, Short.MAX_VALUE, (short) 0, (short) -1));
            case INT32 -> values.addAll(List.of(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1));
            case INT64 -> values.addAll(List.of(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L));
            case FLOAT32 -> values.addAll(List.of(
                    Float.MIN_VALUE,
                    -Float.MAX_VALUE,
                    0f,
                    -0f,
                    Float.NaN,
                    Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY));
            case FLOAT64 -> values.addAll(List.of(
                    Double.MIN_VALUE,
                    -Double.MAX_VALUE,
                    0d,
                    -0d,
                    Double.NaN,
                    Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY));
            case DECIMAL -> {
                BigInteger max = BigInteger.TEN.pow(type.precision()).subtract(BigInteger.ONE);
                values.add(new BigDecimal(max, type.scale()));
                values.add(new BigDecimal(max.negate(), type.scale()));
                values.add(new BigDecimal(BigInteger.ZERO, type.scale()));
                values.add(new BigDecimal(BigInteger.ONE, type.scale()));
            }
            case STRING -> {
                values.addAll(List.of("", "a", "abcdefg", "abcdefgh", "中文", "中文字", "😀", "a\u0000b"));
                if (large) {
                    values.add("x".repeat(70_000));
                }
            }
            case JSON -> values.addAll(List.of("{}", "{\"a\":[1,2,{\"b\":null}]}"));
            case BYTES -> {
                values.addAll(List.of(new byte[0], new byte[] {(byte) 0xFF}, new byte[7], bytes(8)));
                if (large) {
                    values.add(bytes(100_000));
                }
            }
            case DATE -> values.addAll(List.of(
                    LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31), LocalDate.EPOCH, LocalDate.of(1969, 12, 31)));
            case TIME -> values.addAll(
                    List.of(LocalTime.MIN, LocalTime.MAX.withNano(type.precision() > 3 ? 999_999_999 : 999_000_000)));
            case TIMESTAMP -> values.addAll(List.of(
                    FIRST,
                    precise(LAST, type.precision()),
                    LocalDateTime.of(1970, 1, 1, 0, 0),
                    precise(LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_999), type.precision())));
            case TIMESTAMP_LTZ -> values.addAll(List.of(
                    Instant.parse("0001-01-01T00:00:00Z"),
                    precise(Instant.parse("9999-12-31T23:59:59.999999999Z"), type.precision()),
                    Instant.EPOCH,
                    precise(Instant.parse("1969-12-31T23:59:59.999999999Z"), type.precision())));
            case ARRAY -> {
                values.add(List.of());
                List<@Nullable Object> all = new ArrayList<>(boundaries(type.elementType(), large));
                values.add(all);
            }
            case MAP -> {
                values.add(Map.of());
                Map<@Nullable Object, @Nullable Object> map = new LinkedHashMap<>();
                List<@Nullable Object> keys = boundaries(type.keyType(), large);
                List<@Nullable Object> vals = boundaries(type.valueType(), large);
                for (int i = 0; i < keys.size(); i++) {
                    map.put(keys.get(i), vals.get(i % vals.size()));
                }
                values.add(map);
            }
            case ROW -> {
                List<DataType.Field> fields = type.fields();
                values.add(GenericRow.of(TABLE, new Object[fields.size()]));
                Object[] last = new Object[fields.size()];
                for (int i = 0; i < last.length; i++) {
                    List<@Nullable Object> edge = boundaries(fields.get(i).type(), large);
                    last[i] = edge.get(edge.size() - 1);
                }
                values.add(GenericRow.of(TABLE, last));
            }
            case NULL -> {}
        }
        return values;
    }

    static LocalDateTime precise(LocalDateTime time, int precision) {
        return time.withNano(truncate(time.getNano(), precision));
    }

    static Instant precise(Instant time, int precision) {
        return Instant.ofEpochSecond(time.getEpochSecond(), truncate(time.getNano(), precision));
    }

    /** Nanoseconds cut to {@code precision} digits; at most microseconds when it is 6 or less. */
    static int truncate(int nanos, int precision) {
        int unit = (int) Math.pow(10, 9 - Math.min(9, Math.max(precision, 0)));
        return nanos - nanos % unit;
    }

    static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        return bytes;
    }

    /** A random type, nested at most {@code depth} levels. */
    static DataType randomType(Random random, int depth) {
        int pick = random.nextInt(depth > 0 ? 23 : 19);
        return switch (pick) {
            case 0 -> BOOLEAN();
            case 1 -> INT8();
            case 2 -> INT16();
            case 3 -> INT32();
            case 4 -> INT64();
            case 5 -> FLOAT32();
            case 6 -> FLOAT64();
            case 7 -> {
                int precision = 1 + random.nextInt(38);
                yield DECIMAL(precision, random.nextInt(precision + 1));
            }
            case 8, 9 -> STRING();
            case 10 -> JSON();
            case 11 -> BYTES();
            case 12 -> DATE();
            case 13 -> TIME(random.nextInt(10));
            case 14, 15 -> TIMESTAMP(random.nextInt(10));
            case 16 -> TIMESTAMP_LTZ(random.nextInt(10));
            case 17 -> DataTypes.NULL();
            case 18 -> INT32();
            case 19 -> ARRAY(randomType(random, depth - 1));
            case 20 -> MAP(randomType(random, depth - 1), randomType(random, depth - 1));
            default -> {
                int count = 1 + random.nextInt(5);
                List<DataType.Field> fields = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    fields.add(FIELD("f" + i, randomType(random, depth - 1)));
                }
                yield ROW(fields);
            }
        };
    }

    /** A random value of {@code type}; null about one time in eight. */
    static @Nullable Object randomValue(DataType type, Random random) {
        if (type.root() == org.devlive.pipeforge.api.type.TypeRoot.NULL || random.nextInt(8) == 0) {
            return null;
        }
        return switch (type.root()) {
            case BOOLEAN -> random.nextBoolean();
            case INT8 -> (byte) random.nextInt();
            case INT16 -> (short) random.nextInt();
            case INT32 -> random.nextInt();
            case INT64 -> random.nextLong();
            case FLOAT32 -> Float.intBitsToFloat(random.nextInt());
            case FLOAT64 -> Double.longBitsToDouble(random.nextLong());
            case DECIMAL -> {
                BigInteger unscaled =
                        new BigInteger(type.precision() * 3, random).mod(BigInteger.TEN.pow(type.precision()));
                yield new BigDecimal(random.nextBoolean() ? unscaled : unscaled.negate(), type.scale());
            }
            case STRING, JSON -> randomString(random);
            case BYTES -> bytes(random.nextInt(4) == 0 ? random.nextInt(8) : random.nextInt(300));
            case DATE -> LocalDate.ofEpochDay(random.nextLong(
                    LocalDate.of(1, 1, 1).toEpochDay(),
                    LocalDate.of(9999, 12, 31).toEpochDay()));
            case TIME -> {
                long nanos = random.nextLong(LocalTime.MAX.toNanoOfDay() + 1);
                long unit = pow(9 - Math.min(9, type.precision()));
                yield LocalTime.ofNanoOfDay(nanos - nanos % unit);
            }
            case TIMESTAMP -> precise(
                    LocalDateTime.ofEpochSecond(
                            random.nextLong(
                                    FIRST.toEpochSecond(java.time.ZoneOffset.UTC),
                                    LAST.toEpochSecond(java.time.ZoneOffset.UTC)),
                            random.nextInt(1_000_000_000),
                            java.time.ZoneOffset.UTC),
                    type.precision());
            case TIMESTAMP_LTZ -> precise(
                    Instant.ofEpochSecond(
                            random.nextLong(
                                    FIRST.toEpochSecond(java.time.ZoneOffset.UTC),
                                    LAST.toEpochSecond(java.time.ZoneOffset.UTC)),
                            random.nextInt(1_000_000_000)),
                    type.precision());
            case ARRAY -> {
                List<@Nullable Object> list = new ArrayList<>();
                for (int i = random.nextInt(6); i > 0; i--) {
                    list.add(randomValue(type.elementType(), random));
                }
                yield list;
            }
            case MAP -> {
                Map<@Nullable Object, @Nullable Object> map = new LinkedHashMap<>();
                for (int i = random.nextInt(5); i > 0; i--) {
                    map.put(randomValue(type.keyType(), random), randomValue(type.valueType(), random));
                }
                yield map;
            }
            case ROW -> {
                Object[] values = new Object[type.fields().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = randomValue(type.fields().get(i).type(), random);
                }
                yield GenericRow.of(TABLE, values);
            }
            case NULL -> null;
        };
    }

    private static long pow(int exponent) {
        long value = 1;
        for (int i = 0; i < exponent; i++) {
            value *= 10;
        }
        return value;
    }

    private static String randomString(Random random) {
        int length = random.nextInt(4) == 0 ? random.nextInt(5) : random.nextInt(200);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int kind = random.nextInt(10);
            text.appendCodePoint(
                    kind < 6
                            ? 'a' + random.nextInt(26)
                            : kind < 9 ? 0x4E00 + random.nextInt(0x5000) : 0x1F600 + random.nextInt(64));
        }
        return text.toString();
    }

    /** A form that equals compares by content: rows as lists, bytes as hex, maps as entry lists in order. */
    static @Nullable Object canonical(@Nullable Object value) {
        if (value instanceof byte[] bytes) {
            return "0x" + HexFormat.of().formatHex(bytes);
        }
        if (value instanceof Row row) {
            List<@Nullable Object> fields = new ArrayList<>();
            fields.add(row.kind());
            fields.add(row.table());
            for (int i = 0; i < row.arity(); i++) {
                fields.add(canonical(row.get(i)));
            }
            return fields;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Values::canonical).toList();
        }
        if (value instanceof Map<?, ?> map) {
            List<@Nullable Object> entries = new ArrayList<>();
            map.forEach((k, v) -> entries.add(Arrays.asList(canonical(k), canonical(v))));
            return entries;
        }
        return value;
    }
}
