/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.row;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.devlive.pipeforge.api.type.DataTypes.ARRAY;
import static org.devlive.pipeforge.api.type.DataTypes.DECIMAL;
import static org.devlive.pipeforge.api.type.DataTypes.FIELD;
import static org.devlive.pipeforge.api.type.DataTypes.INT32;
import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.ROW;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;
import static org.devlive.pipeforge.api.type.DataTypes.TIMESTAMP;
import static org.devlive.pipeforge.engine.network.row.Values.TABLE;
import static org.devlive.pipeforge.engine.network.row.Values.canonical;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.RowKind;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RowCodecTest {

    static final Path GOLDEN = Path.of("..", "protocol-compat", "row", "v1", "sample.bin");

    static final TableId SMALL = TableId.of("small");

    static List<DataType.Field> fields(List<DataType> types) {
        List<DataType.Field> fields = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            fields.add(FIELD("c" + i, types.get(i)));
        }
        return fields;
    }

    static RowCodec codec(TableId table, List<DataType> types) {
        return new RowCodec(List.of(new TableLayout(5, table, fields(types))));
    }

    static byte[] encode(RowCodec codec, Row row, @Nullable Long eventTime) {
        RowBuffer out = new RowBuffer(16);
        codec.encode(row, eventTime, out);
        return out.toByteArray();
    }

    static RowCodec.Decoded roundTrip(RowCodec codec, Row row, @Nullable Long eventTime) {
        byte[] bytes = encode(codec, row, eventTime);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        RowCodec.Decoded decoded = codec.decode(in);
        assertThat(in.hasRemaining()).as("the row is read to its end").isFalse();
        assertThat(canonical(decoded.row())).as("%s", row).isEqualTo(canonical(row));
        assertThat(decoded.eventTime()).isEqualTo(eventTime);
        assertThat(encode(codec, decoded.row(), eventTime))
                .as("encoding is stable")
                .isEqualTo(bytes);
        return decoded;
    }

    static String hex(byte[] bytes) {
        return HexFormat.ofDelimiter(" ").formatHex(bytes);
    }

    @Test
    void writesTheBytesOfTheSpecification() {
        RowCodec codec = codec(SMALL, List.of(INT32(), STRING()));
        // length 19, header INSERT, table 5, no nulls, 7, "hi" inline (0x80 | 2)
        assertThat(hex(encode(codec, GenericRow.of(SMALL, 7, "hi"), null)))
                .isEqualTo("13 00 05 00 00 00 00 00 00 00 00 07 82 68 69 00 00 00 00 00");
        // a string of 8 bytes is referenced: offset 19 from the header, length 8
        assertThat(hex(encode(codec, GenericRow.of(SMALL, -1, "abcdefgh"), null)))
                .isEqualTo("1b 00 05 00 ff ff ff ff ff ff ff ff 00 00 00 13 00 00 00 08 61 62 63 64 65 66 67 68");
        // DELETE with an event time: header 0b111; second column NULL; the time leads the variable section
        assertThat(hex(encode(codec, GenericRow.ofKind(SMALL, RowKind.DELETE, 1, null), 1000L)))
                .isEqualTo("1b 07 05 02 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03 e8");
        for (RowKind kind : RowKind.values()) {
            byte[] bytes = encode(codec, GenericRow.ofKind(SMALL, kind, 1, "x"), null);
            assertThat(bytes[1]).as("%s", kind).isEqualTo((byte) kind.ordinal());
        }
    }

    @Test
    void writesFloatsInTheLowBytesAndSignExtendsIntegers() {
        RowCodec codec = codec(SMALL, List.of(DataTypes.FLOAT32(), DataTypes.INT8()));
        assertThat(hex(encode(codec, GenericRow.of(SMALL, 1.5f, (byte) -2), null)))
                .isEqualTo("13 00 05 00 00 00 00 00 3f c0 00 00 ff ff ff ff ff ff ff fe");
    }

    @Test
    void roundTripsTheBoundariesOfEveryType() {
        for (DataType type : Values.types()) {
            RowCodec codec = codec(TABLE, List.of(type, INT32()));
            for (Object value : Values.boundaries(type)) {
                roundTrip(codec, GenericRow.of(TABLE, value, 42), null);
            }
        }
    }

    @Test
    void roundTripsRowKindsAndEventTimes() {
        RowCodec codec = codec(TABLE, List.of(INT32(), STRING()));
        for (RowKind kind : RowKind.values()) {
            for (Long time : new Long[] {null, 0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 1_763_000_000_000L}) {
                assertThat(roundTrip(codec, GenericRow.ofKind(TABLE, kind, 1, "text that is longer"), time)
                                .row()
                                .kind())
                        .isEqualTo(kind);
            }
        }
    }

    @Test
    void readsManyRowsOfSeveralTablesFromOneBuffer() {
        List<DataType> wide = Values.types();
        RowCodec codec = new RowCodec(
                List.of(new TableLayout(0, TABLE, fields(wide)), new TableLayout(1, SMALL, fields(List.of(INT64())))));
        Random random = new Random(7);
        RowBuffer out = new RowBuffer(64);
        List<Object> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Row row;
            if (i % 3 == 0) {
                row = GenericRow.of(SMALL, (long) i);
            } else {
                Object[] values = new Object[wide.size()];
                for (int c = 0; c < values.length; c++) {
                    values[c] = Values.randomValue(wide.get(c), random);
                }
                row = GenericRow.ofKind(TABLE, RowKind.values()[i % 4], values);
            }
            codec.encode(row, i % 2 == 0 ? (long) i : null, out);
            expected.add(canonical(row));
        }
        List<RowCodec.Decoded> rows = codec.decodeAll(out.asByteBuffer());
        assertThat(rows).extracting(d -> canonical(d.row())).containsExactlyElementsOf(expected);
        assertThat(rows.get(2).eventTime()).isEqualTo(2L);
        assertThat(rows.get(1).eventTime()).isNull();
    }

    @Test
    void randomTypesAndRowsRoundTrip() {
        Random random = new Random(20261115);
        for (int schema = 0; schema < 300; schema++) {
            List<DataType> types = new ArrayList<>();
            for (int i = 1 + random.nextInt(12); i > 0; i--) {
                types.add(Values.randomType(random, 3));
            }
            RowCodec codec = codec(TABLE, types);
            for (int row = 0; row < 20; row++) {
                Object[] values = new Object[types.size()];
                for (int c = 0; c < values.length; c++) {
                    values[c] = Values.randomValue(types.get(c), random);
                }
                Long time = random.nextBoolean() ? random.nextLong() : null;
                roundTrip(codec, GenericRow.ofKind(TABLE, RowKind.values()[random.nextInt(4)], values), time);
            }
        }
    }

    /** Rows of every type in version 1 of the format; the file must keep reading the same. */
    static List<Object[]> goldenRows() {
        List<DataType> types = Values.types();
        List<Object[]> rows = new ArrayList<>();
        for (int pick = 1; pick <= 3; pick++) {
            Object[] values = new Object[types.size()];
            for (int c = 0; c < values.length; c++) {
                List<@Nullable Object> edge = Values.boundaries(types.get(c), false);
                values[c] = edge.get(Math.min(pick, edge.size() - 1));
            }
            rows.add(values);
        }
        rows.add(new Object[types.size()]);
        return rows;
    }

    @Test
    void readsTheGoldenFileOfVersion1() throws Exception {
        RowCodec codec = codec(TABLE, Values.types());
        RowBuffer out = new RowBuffer(64);
        List<Object> expected = new ArrayList<>();
        List<Object[]> rows = goldenRows();
        for (int i = 0; i < rows.size(); i++) {
            Row row = GenericRow.ofKind(TABLE, RowKind.values()[i % 4], rows.get(i));
            codec.encode(row, i == 1 ? 1_763_164_800_000L : null, out);
            expected.add(canonical(row));
        }
        byte[] current = out.toByteArray();
        if (!Files.exists(GOLDEN)) {
            Files.createDirectories(GOLDEN.getParent());
            Files.write(GOLDEN, current);
        }
        byte[] golden = Files.readAllBytes(GOLDEN);
        // the rows of format 1 still read the same…
        assertThat(codec.decodeAll(ByteBuffer.wrap(golden)))
                .extracting(d -> canonical(d.row()))
                .containsExactlyElementsOf(expected);
        // …and are still written the same
        assertThat(current).isEqualTo(golden);
    }

    @Test
    void refusesValuesThatDoNotFitTheirColumn() {
        TableId t = SMALL;
        DataType address = ROW(FIELD("city", STRING()), FIELD("zip", INT32()));
        RowCodec codec = codec(t, List.of(INT32(), address, DECIMAL(5, 2), TIMESTAMP(6), ARRAY(INT64())));
        Row ok = GenericRow.of(t, 1, GenericRow.of(t, "Berlin", 10115), new BigDecimal("1.50"), null, List.of(1L));
        roundTrip(codec, ok, null);

        record Case(Object[] values, ErrorCode code, String column, String reason) {}
        for (Case c : List.of(
                new Case(
                        new Object[] {"1", null, null, null, null},
                        ErrorCode.TYPE_CONVERSION,
                        "c0",
                        "a value of java.lang.String"),
                new Case(
                        new Object[] {1, GenericRow.of(t, 5, 1), null, null, null},
                        ErrorCode.TYPE_CONVERSION,
                        "c1.city",
                        "a value of java.lang.Integer"),
                new Case(
                        new Object[] {1, GenericRow.of(t, "x"), null, null, null},
                        ErrorCode.TYPE_CONVERSION,
                        "c1",
                        "a row of 1 values"),
                new Case(
                        new Object[] {1, null, new BigDecimal("1.505"), null, null},
                        ErrorCode.VALUE_OUT_OF_RANGE,
                        "c2",
                        "1.505 has more than 2 decimals"),
                new Case(
                        new Object[] {1, null, new BigDecimal("1000"), null, null},
                        ErrorCode.VALUE_OUT_OF_RANGE,
                        "c2",
                        "1000 needs 6 digits with 2 decimals, more than 5"),
                new Case(
                        new Object[] {1, null, null, LocalDateTime.MAX, null},
                        ErrorCode.VALUE_OUT_OF_RANGE,
                        "c3",
                        "more microseconds from 1970 than fit in 64 bits"),
                new Case(
                        new Object[] {1, null, null, null, List.of(1)},
                        ErrorCode.TYPE_CONVERSION,
                        "c4[]",
                        "a value of java.lang.Integer"))) {
            assertThatThrownBy(() -> encode(codec, GenericRow.of(t, c.values()), null))
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                        assertThat(e.code()).isEqualTo(c.code());
                        assertThat(e.params())
                                .containsEntry("column", c.column())
                                .containsEntry("reason", c.reason());
                    });
        }
        RowCodec nulls = codec(t, List.of(DataTypes.NULL()));
        assertThatThrownBy(() -> encode(nulls, GenericRow.of(t, 1), null))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.TYPE_CONVERSION));
    }

    @Test
    void refusesRowsOfOtherTablesAndADirectoryWithDuplicates() {
        RowCodec codec = codec(SMALL, List.of(INT32()));
        assertThatThrownBy(() -> encode(codec, GenericRow.of(TABLE, 1), null))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL);
                    assertThat(e.getMessage()).contains("which is not in the table directory");
                });
        assertThatThrownBy(() -> encode(codec, GenericRow.of(SMALL, 1, 2), null))
                .hasMessageContaining("a row of 2 values for table small with 1 columns");
        List<DataType.Field> one = fields(List.of(INT32()));
        assertThatThrownBy(() -> new RowCodec(List.of(new TableLayout(1, SMALL, one), new TableLayout(1, TABLE, one))))
                .hasMessage("two tables have the number 1");
        assertThatThrownBy(() -> new RowCodec(List.of(new TableLayout(1, SMALL, one), new TableLayout(2, SMALL, one))))
                .hasMessage("table small is in the directory twice");
        assertThatThrownBy(() -> new TableLayout(-1, SMALL, one)).isInstanceOf(IllegalArgumentException.class);
    }

    static void assertMalformed(RowCodec codec, String bytes, String reason) {
        byte[] row = HexFormat.ofDelimiter(" ").parseHex(bytes);
        assertThatThrownBy(() -> codec.decode(ByteBuffer.wrap(row)))
                .as(bytes)
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL);
                    assertThat(e.getMessage())
                            .contains("a malformed row in a data buffer")
                            .contains(reason);
                });
    }

    @Test
    void refusesMalformedRows() {
        RowCodec codec = codec(SMALL, List.of(INT32(), STRING()));
        String slots = " 00 00 00 00 00 00 00 07 82 68 69 00 00 00 00 00";
        assertMalformed(codec, "14 00 05 00" + slots, "");
        assertMalformed(codec, "13 08 05 00" + slots, "unknown bits in row header 1000");
        assertMalformed(codec, "13 00 06 00" + slots, "table 6 is not in the table directory");
        assertMalformed(codec, "0b 00 05 00 00 00 00 00 00 00 00 07", "end after the row");
        assertMalformed(
                codec,
                "13 00 05 00 00 00 00 00 00 00 00 07 00 00 00 13 00 00 00 08",
                "a value at 19 of 8 bytes outside the row of 19");
        assertMalformed(codec, "13 00 05 00 00 00 00 00 00 00 00 07 8f 00 00 00 00 00 00 00", "inline value of 15");
        assertMalformed(codec, "ff ff ff ff ff ff ff ff ff ff ff", "varint");

        RowCodec decimals = codec(SMALL, List.of(DECIMAL(30, 0)));
        assertMalformed(decimals, "0b 00 05 00 81 01 00 00 00 00 00 00", "an inline value where none is allowed");
        RowCodec times = codec(SMALL, List.of(TIMESTAMP(9)));
        assertMalformed(
                times, "15 00 05 00 00 00 00 0b 00 00 00 02 00 00 00 00 00 00 00 00 00 00", "a timestamp of 2 bytes");
        assertMalformed(
                times,
                "15 00 05 00 00 00 00 0b 00 00 00 0a 00 00 00 00 00 00 00 00 03 e8",
                "1000 nanoseconds within a µs");
        RowCodec arrays = codec(SMALL, List.of(ARRAY(INT32())));
        assertMalformed(arrays, "0d 00 05 00 00 00 00 0b 00 00 00 02 05 00", "end after the row");
        RowCodec nulls = codec(SMALL, List.of(DataTypes.NULL()));
        assertMalformed(nulls, "0b 00 05 00 00 00 00 00 00 00 00 00", "a value in a column of type NULL");
    }
}
