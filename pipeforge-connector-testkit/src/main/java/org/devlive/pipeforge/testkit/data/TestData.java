/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.data;

import static org.devlive.pipeforge.api.type.DataTypes.ARRAY;
import static org.devlive.pipeforge.api.type.DataTypes.BOOLEAN;
import static org.devlive.pipeforge.api.type.DataTypes.BYTES;
import static org.devlive.pipeforge.api.type.DataTypes.DATE;
import static org.devlive.pipeforge.api.type.DataTypes.DECIMAL;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.jspecify.annotations.Nullable;

/**
 * Test data: tables of every logical type, their boundary values, and rows generated from a seed,
 * so that a failing test can be repeated exactly.
 */
public final class TestData {

    /** The table the testkit reads and writes unless a connector asks for another. */
    public static final TableId TABLE = TableId.of("testkit", "items");

    /** The column types of the {@code types} group, one representative per logical type. */
    public static final List<DataType> TYPES = List.of(
            BOOLEAN(),
            INT8(),
            INT16(),
            INT32(),
            INT64(),
            FLOAT32(),
            FLOAT64(),
            DECIMAL(10, 2),
            DECIMAL(38, 10),
            STRING(),
            BYTES(),
            DATE(),
            TIME(3),
            TIMESTAMP(6),
            TIMESTAMP_LTZ(6),
            ARRAY(INT32()),
            MAP(STRING(), INT64()),
            ROW(new DataType.Field("a", INT32()), new DataType.Field("b", STRING())),
            JSON());

    /** The length of the long string among the boundary values of {@code STRING}. */
    public static final int LONG_STRING = 64 * 1024;

    private TestData() {}

    /** A table {@code (id INT64 NOT NULL primary key, value <type>)}. */
    public static CatalogTable table(DataType type) {
        return CatalogTable.of(TABLE, List.of(Column.of("id", INT64().notNull()), Column.of("value", type)))
                .withPrimaryKey("id");
    }

    /** A table with an id and a few columns of common types, for row counts and splits. */
    public static CatalogTable wideTable() {
        return CatalogTable.of(
                        TABLE,
                        List.of(
                                Column.of("id", INT64().notNull()),
                                Column.of("name", STRING()),
                                Column.of("amount", DECIMAL(12, 2)),
                                Column.of("created_at", TIMESTAMP(3)),
                                Column.of("active", BOOLEAN())))
                .withPrimaryKey("id");
    }

    /** The name of a type in test names, e.g. {@code DECIMAL(10, 2)}. */
    public static String name(DataType type) {
        return type.withNullability(true).toString();
    }

    /**
     * The rows that exercise {@code type}: NULL, every boundary value and a few random values, in
     * the table {@link #table(DataType)}.
     */
    public static TestTable typeTable(DataType type) {
        CatalogTable table = table(type);
        List<Row> rows = new ArrayList<>();
        long id = 0;
        rows.add(GenericRow.of(TABLE, id++, null));
        for (Object value : boundaries(type)) {
            rows.add(GenericRow.of(TABLE, id++, value));
        }
        Random random = new Random(type.hashCode());
        for (int i = 0; i < 5; i++) {
            rows.add(GenericRow.of(TABLE, id++, value(type, random)));
        }
        return new TestTable(name(type), table, rows);
    }

    /** Rows {@code 0 .. count-1} of {@code table}, generated from {@code seed}; nullable columns are NULL in about 10% of rows. */
    public static TestTable rows(String name, CatalogTable table, int count, long seed) {
        Random random = new Random(seed);
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object[] values = new Object[table.columns().size()];
            for (int c = 0; c < values.length; c++) {
                Column column = table.columns().get(c);
                if (c == 0 && column.type().root() == TypeRoot.INT64) {
                    values[c] = (long) i;
                } else if (column.type().root() == TypeRoot.NULL
                        || (column.type().isNullable() && random.nextInt(10) == 0)) {
                    values[c] = null;
                } else {
                    values[c] = value(column.type(), random);
                }
            }
            rows.add(GenericRow.of(table.id(), values));
        }
        return new TestTable(name, table, rows);
    }

    /** The extreme and special values of a type: minimum, maximum, zero, empty, longest, earliest, latest. */
    public static List<Object> boundaries(DataType type) {
        return switch (type.root()) {
            case BOOLEAN -> List.of(true, false);
            case INT8 -> List.of(Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) 0);
            case INT16 -> List.of(Short.MIN_VALUE, Short.MAX_VALUE, (short) 0);
            case INT32 -> List.of(Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
            case INT64 -> List.of(Long.MIN_VALUE, Long.MAX_VALUE, 0L);
            case FLOAT32 -> List.of(-Float.MAX_VALUE, Float.MAX_VALUE, Float.MIN_VALUE, 0.0f, -1.5f);
            case FLOAT64 -> List.of(-Double.MAX_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, 0.0, -1.5);
            case DECIMAL -> {
                BigDecimal max = BigDecimal.TEN
                        .pow(type.precision() - type.scale())
                        .subtract(BigDecimal.ONE.movePointLeft(type.scale()))
                        .setScale(type.scale());
                yield List.of(
                        max,
                        max.negate(),
                        BigDecimal.ZERO.setScale(type.scale()),
                        BigDecimal.ONE.movePointLeft(type.scale()));
            }
            case STRING, JSON -> type.root() == TypeRoot.JSON
                    ? List.of("{}", "[]", "{\"a\":[1,2.5,{\"b\":null}],\"c\":\"文字\"}")
                    : List.of("", "héllo 世界 😀", "'quoted' \"double\" \\ back\nnew line\ttab", longString(type));
            case BYTES -> {
                byte[] all = new byte[256];
                for (int i = 0; i < all.length; i++) {
                    all[i] = (byte) i;
                }
                yield List.of(new byte[0], all);
            }
            case DATE -> List.of(LocalDate.of(1, 1, 1), LocalDate.of(1970, 1, 1), LocalDate.of(9999, 12, 31));
            case TIME -> List.of(
                    LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59, 999_999_999).truncatedTo(unit(type.precision())));
            case TIMESTAMP -> List.of(
                    LocalDateTime.of(1, 1, 1, 0, 0),
                    LocalDateTime.of(1970, 1, 1, 0, 0),
                    LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999).truncatedTo(unit(type.precision())));
            case TIMESTAMP_LTZ -> List.of(
                    Instant.parse("0001-01-01T00:00:00Z"),
                    Instant.EPOCH,
                    Instant.parse("9999-12-31T23:59:59.999999999Z").truncatedTo(unit(type.precision())));
            case ARRAY -> List.of(List.of(), Arrays.asList(value(type.elementType(), new Random(1)), null));
            case MAP -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                map.put(value(type.keyType(), new Random(1)), value(type.valueType(), new Random(2)));
                map.put(value(type.keyType(), new Random(3)), null);
                yield List.of(Map.of(), map);
            }
            case ROW -> {
                Object[] values = new Object[type.fields().size()];
                yield List.of(GenericRow.of(TABLE, values), nestedRow(type, new Random(1)));
            }
            case NULL -> List.of();
        };
    }

    /** A random non-null value of {@code type}. */
    public static Object value(DataType type, Random random) {
        return switch (type.root()) {
            case BOOLEAN -> random.nextBoolean();
            case INT8 -> (byte) random.nextInt();
            case INT16 -> (short) random.nextInt();
            case INT32 -> random.nextInt();
            case INT64 -> random.nextLong();
            case FLOAT32 -> (random.nextFloat() - 0.5f) * 1e6f;
            case FLOAT64 -> (random.nextDouble() - 0.5) * 1e12;
            case DECIMAL -> new BigDecimal(new BigInteger(Math.min(120, type.precision() * 3), random), type.scale())
                    .remainder(BigDecimal.TEN.pow(type.precision() - type.scale()))
                    .setScale(type.scale());
            case STRING -> {
                int max = type.length() == null ? 32 : Math.min(32, type.length());
                StringBuilder text = new StringBuilder();
                int length = random.nextInt(max + 1);
                for (int i = 0; i < length; i++) {
                    text.append((char) ('a' + random.nextInt(26)));
                }
                yield text.toString();
            }
            case JSON -> "{\"n\":" + random.nextInt(1000) + "}";
            case BYTES -> {
                byte[] bytes = new byte[random.nextInt(17)];
                random.nextBytes(bytes);
                yield bytes;
            }
            case DATE -> LocalDate.ofEpochDay(random.nextInt(60_000) - 20_000);
            case TIME -> LocalTime.ofNanoOfDay(Math.floorMod(random.nextLong(), 86_400_000_000_000L))
                    .truncatedTo(unit(type.precision()));
            case TIMESTAMP -> LocalDateTime.of(LocalDate.ofEpochDay(random.nextInt(60_000) - 20_000), LocalTime.NOON)
                    .plusNanos(Math.floorMod(random.nextLong(), 86_400_000_000_000L))
                    .truncatedTo(unit(type.precision()));
            case TIMESTAMP_LTZ -> Instant.ofEpochSecond(random.nextInt(), random.nextInt(1_000_000_000))
                    .truncatedTo(unit(type.precision()));
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    list.add(value(type.elementType(), random));
                }
                yield list;
            }
            case MAP -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    map.put(value(type.keyType(), random), value(type.valueType(), random));
                }
                yield map;
            }
            case ROW -> nestedRow(type, random);
            case NULL -> throw new IllegalArgumentException("The NULL type has no values");
        };
    }

    private static Row nestedRow(DataType type, Random random) {
        Object[] values = new Object[type.fields().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = value(type.fields().get(i).type(), random);
        }
        return GenericRow.of(TABLE, values);
    }

    private static String longString(DataType type) {
        @Nullable Integer length = type.length();
        return "x".repeat(length == null ? LONG_STRING : Math.min(length, LONG_STRING));
    }

    private static ChronoUnit unit(int precision) {
        if (precision >= 9) {
            return ChronoUnit.NANOS;
        }
        if (precision >= 6) {
            return ChronoUnit.MICROS;
        }
        if (precision >= 3) {
            return ChronoUnit.MILLIS;
        }
        return ChronoUnit.SECONDS;
    }
}
