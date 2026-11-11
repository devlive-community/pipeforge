/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.jspecify.annotations.Nullable;

/**
 * Generates row {@code n} of a table. Every row depends only on the seed, the table and {@code n},
 * so the same seed gives the same rows whatever the splits and the parallelism.
 */
final class RowGenerator {

    private static final long DAY_NANOS = 86_400_000_000_000L;
    private static final long EPOCH_2000 = LocalDate.of(2000, 1, 1).toEpochDay();
    private static final int DAYS = 365 * 30;

    private final FakeSettings settings;
    private final int[] eventColumn;

    RowGenerator(FakeSettings settings) {
        this.settings = settings;
        this.eventColumn = new int[settings.tables().size()];
        FakeSettings.EventTime eventTime = settings.eventTime();
        for (int t = 0; t < eventColumn.length; t++) {
            eventColumn[t] =
                    eventTime == null ? -1 : settings.tables().get(t).table().indexOf(eventTime.column());
        }
    }

    /** Row {@code n} of table {@code t}. */
    Row row(int t, long n) {
        FakeTable fake = settings.tables().get(t);
        List<Row> data = fake.data();
        if (data != null) {
            return data.get((int) n);
        }
        CatalogTable table = fake.table();
        SplittableRandom random = new SplittableRandom(mix(settings.seed(), t, n));
        List<Column> columns = table.columns();
        Object[] values = new Object[columns.size()];
        for (int c = 0; c < values.length; c++) {
            Column column = columns.get(c);
            boolean key = table.primaryKey().contains(column.name());
            if (key) {
                values[c] = key(column.type(), n, random, table.id());
            } else if (column.type().isNullable() && random.nextDouble() < settings.nullRatio()) {
                values[c] = null;
            } else {
                values[c] = value(column.type(), random, table.id());
            }
        }
        if (eventColumn[t] >= 0) {
            values[eventColumn[t]] = eventValue(columns.get(eventColumn[t]).type(), eventTime(t, n));
        }
        if (settings.badRowRatio() > 0 && random.nextDouble() < settings.badRowRatio()) {
            breakValue(table, values);
        }
        return GenericRow.of(table.id(), values);
    }

    /** The event time of row {@code n} in epoch milliseconds, or -1 without event time. */
    long eventTime(int t, long n) {
        FakeSettings.EventTime eventTime = settings.eventTime();
        if (eventTime == null) {
            return -1;
        }
        List<Row> data = settings.tables().get(t).data();
        if (data != null) {
            Object value = data.get((int) n).get(eventColumn[t]);
            return value == null ? -1 : millis(value);
        }
        long time = eventTime.start().toEpochMilli() + n * eventTime.step().toMillis();
        long late = eventTime.maxOutOfOrder().toMillis();
        if (late > 0) {
            time -= new SplittableRandom(mix(settings.seed() ^ 0x5DEECE66DL, t, n)).nextLong(late + 1);
        }
        return time;
    }

    private static long millis(Object value) {
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        return ((Number) value).longValue();
    }

    private static Object eventValue(DataType type, long millis) {
        Instant instant = Instant.ofEpochMilli(millis);
        return switch (type.root()) {
            case TIMESTAMP -> LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            case TIMESTAMP_LTZ -> instant;
            default -> millis;
        };
    }

    private static Object key(DataType type, long n, SplittableRandom random, TableId table) {
        return switch (type.root()) {
            case INT8 -> (byte) n;
            case INT16 -> (short) n;
            case INT32 -> (int) n;
            case INT64 -> n;
            case DECIMAL -> BigDecimal.valueOf(n).setScale(type.scale());
            case STRING -> "key-" + n;
            default -> value(type, random, table);
        };
    }

    /** Makes the first value that can be broken violate its column type. */
    private static void breakValue(CatalogTable table, Object[] values) {
        List<Column> columns = table.columns();
        for (int c = 0; c < values.length; c++) {
            Column column = columns.get(c);
            if (table.primaryKey().contains(column.name())) {
                continue;
            }
            DataType type = column.type();
            if (!type.isNullable()) {
                values[c] = null;
                return;
            }
            if (type.root() == TypeRoot.STRING && type.length() != null) {
                values[c] = "x".repeat(type.length() + 1);
                return;
            }
            if (type.root() == TypeRoot.DECIMAL) {
                values[c] = BigDecimal.TEN.pow(type.precision() - type.scale()).setScale(type.scale());
                return;
            }
        }
    }

    static @Nullable Object value(DataType type, SplittableRandom random, TableId table) {
        return switch (type.root()) {
            case BOOLEAN -> random.nextBoolean();
            case INT8 -> (byte) random.nextInt();
            case INT16 -> (short) random.nextInt();
            case INT32 -> random.nextInt(1_000_000);
            case INT64 -> random.nextLong(1_000_000_000L);
            case FLOAT32 -> (float) (random.nextDouble() * 10_000);
            case FLOAT64 -> random.nextDouble() * 1_000_000;
            case DECIMAL -> {
                int digits = Math.min(type.precision() - type.scale(), 12);
                long whole = digits <= 0 ? 0 : random.nextLong((long) Math.pow(10, digits));
                long fraction =
                        type.scale() == 0 ? 0 : random.nextLong((long) Math.pow(10, Math.min(type.scale(), 12)));
                yield new BigDecimal(whole + "." + fraction).setScale(type.scale(), java.math.RoundingMode.DOWN);
            }
            case STRING -> {
                Integer length = type.length();
                int max = length == null ? 16 : Math.min(length, 16);
                StringBuilder text = new StringBuilder();
                for (int i = random.nextInt(1, Math.max(2, max + 1)); i > 0; i--) {
                    text.append((char) ('a' + random.nextInt(26)));
                }
                yield text.length() > max ? text.substring(0, max) : text.toString();
            }
            case JSON -> "{\"n\":" + random.nextInt(1000) + "}";
            case BYTES -> {
                Integer length = type.length();
                byte[] bytes = new byte[length == null ? 8 : Math.min(length, 8)];
                random.nextBytes(bytes);
                yield bytes;
            }
            case DATE -> LocalDate.ofEpochDay(EPOCH_2000 + random.nextInt(DAYS));
            case TIME -> LocalTime.ofNanoOfDay(random.nextLong(DAY_NANOS)).truncatedTo(unit(type.precision()));
            case TIMESTAMP -> LocalDateTime.of(
                            LocalDate.ofEpochDay(EPOCH_2000 + random.nextInt(DAYS)), LocalTime.MIDNIGHT)
                    .plusNanos(random.nextLong(DAY_NANOS))
                    .truncatedTo(unit(type.precision()));
            case TIMESTAMP_LTZ -> Instant.ofEpochSecond(
                            (EPOCH_2000 + random.nextInt(DAYS)) * 86_400L, random.nextInt(1_000_000_000))
                    .truncatedTo(unit(type.precision()));
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    list.add(value(type.elementType(), random, table));
                }
                yield list;
            }
            case MAP -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    map.put(value(type.keyType(), random, table), value(type.valueType(), random, table));
                }
                yield map;
            }
            case ROW -> {
                Object[] values = new Object[type.fields().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = value(type.fields().get(i).type(), random, table);
                }
                yield GenericRow.of(table, values);
            }
            case NULL -> null;
        };
    }

    private static ChronoUnit unit(int precision) {
        if (precision >= 9) {
            return ChronoUnit.NANOS;
        }
        if (precision >= 6) {
            return ChronoUnit.MICROS;
        }
        return precision >= 3 ? ChronoUnit.MILLIS : ChronoUnit.SECONDS;
    }

    /** A well-mixed seed from the job seed, the table and the row (SplitMix64 finalizer). */
    static long mix(long seed, int table, long row) {
        long z = seed + 0x9E3779B97F4A7C15L * (table + 1) + 0xBF58476D1CE4E5B9L * row;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
