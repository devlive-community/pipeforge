/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.devlive.pipeforge.api.type.DataTypes.ARRAY;
import static org.devlive.pipeforge.api.type.DataTypes.BYTES;
import static org.devlive.pipeforge.api.type.DataTypes.DATE;
import static org.devlive.pipeforge.api.type.DataTypes.DECIMAL;
import static org.devlive.pipeforge.api.type.DataTypes.FLOAT32;
import static org.devlive.pipeforge.api.type.DataTypes.FLOAT64;
import static org.devlive.pipeforge.api.type.DataTypes.INT32;
import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.JSON;
import static org.devlive.pipeforge.api.type.DataTypes.MAP;
import static org.devlive.pipeforge.api.type.DataTypes.ROW;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;
import static org.devlive.pipeforge.api.type.DataTypes.TIME;
import static org.devlive.pipeforge.api.type.DataTypes.TIMESTAMP;
import static org.devlive.pipeforge.api.type.DataTypes.TIMESTAMP_LTZ;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.junit.jupiter.api.Test;

class ValuesTest {

    private final Values values = new Values(ZoneId.of("Asia/Shanghai"));

    @Test
    void scalarsHaveOneTextForm() {
        assertThat(values.text(null, INT32())).isEqualTo("NULL");
        assertThat(values.text(new BigDecimal("1E+3"), DECIMAL(10, 0))).isEqualTo("1000");
        assertThat(values.text(new byte[] {0, 15, (byte) 255}, BYTES())).isEqualTo("0x000fff");
        assertThat(values.text(LocalDate.of(1, 1, 1), DATE())).isEqualTo("0001-01-01");
        assertThat(values.text(LocalTime.of(9, 5), TIME(3))).isEqualTo("09:05:00.000");
        assertThat(values.text(LocalTime.of(9, 5), TIME(0))).isEqualTo("09:05:00");
        assertThat(values.text(LocalDateTime.of(2026, 11, 7, 12, 30, 0, 123_000_000), TIMESTAMP(3)))
                .isEqualTo("2026-11-07 12:30:00.123");
        assertThat(values.text(Instant.parse("2026-11-07T04:30:00Z"), TIMESTAMP_LTZ(0)))
                .isEqualTo("2026-11-07 12:30:00+08:00");
        // Before 1901 Shanghai used its local mean time, an offset with seconds.
        assertThat(values.text(Instant.parse("0001-01-01T00:00:00Z"), TIMESTAMP_LTZ(0)))
                .isEqualTo("0001-01-01 08:05:43+08:05:43");
        assertThat(values.text(Instant.parse("9999-12-31T23:59:59Z"), TIMESTAMP_LTZ(0)))
                .isEqualTo("+10000-01-01 07:59:59+08:00");
        assertThat(values.text(Float.NaN, FLOAT32())).isEqualTo("NaN");
        assertThat(values.text(LocalTime.of(1, 2, 3, 400_000_000), null)).isEqualTo("01:02:03.400");
        assertThat(values.text(LocalDateTime.of(2026, 1, 1, 0, 0, 0, 1_000), null))
                .isEqualTo("2026-01-01 00:00:00.000001");
        assertThat(values.text(Instant.ofEpochSecond(0, 1), null)).isEqualTo("1970-01-01 08:00:00.000000001+08:00");
        assertThat(values.text("{\"a\":1}", JSON())).isEqualTo("{\"a\":1}");
    }

    @Test
    void nestedValuesArePrintedAsJson() {
        DataType row = ROW(new DataType.Field("a", INT32()), new DataType.Field("b", STRING()));
        assertThat(values.text(Arrays.asList(1, null), ARRAY(INT32()))).isEqualTo("[1,null]");
        assertThat(values.text(GenericRow.of(TableId.of("r"), 1, "x"), row)).isEqualTo("{\"a\":1,\"b\":\"x\"}");
        Map<Object, Object> byString = new LinkedHashMap<>();
        byString.put("k", 1L);
        byString.put("n", null);
        assertThat(values.text(byString, MAP(STRING(), INT64()))).isEqualTo("{\"k\":1,\"n\":null}");
        assertThat(values.text(Map.of(7, "seven"), MAP(INT32(), STRING()))).isEqualTo("[[7,\"seven\"]]");
        // Without a type, the shape follows the values.
        assertThat(values.text(Map.of(7, "seven"), null)).isEqualTo("[[7,\"seven\"]]");
        assertThat(values.text(Map.of("k", 1), null)).isEqualTo("{\"k\":1}");
        assertThat(values.text(GenericRow.of(TableId.of("r"), 1, "x"), null)).isEqualTo("[1,\"x\"]");
    }

    @Test
    void jsonValuesKeepTheirTypes() {
        assertThat(values.json(new BigDecimal("12.50"), DECIMAL(10, 2))).isEqualTo("12.50");
        assertThat(values.json(new BigDecimal("1E+20"), DECIMAL(38, 0))).isEqualTo("100000000000000000000");
        assertThat(values.json(Double.POSITIVE_INFINITY, FLOAT64())).isEqualTo("\"Infinity\"");
        assertThat(values.json(Float.NEGATIVE_INFINITY, FLOAT32())).isEqualTo("\"-Infinity\"");
        assertThat(values.json(1.5f, FLOAT32())).isEqualTo("1.5");
        assertThat(values.json(new byte[] {1, 2, 3}, BYTES())).isEqualTo("\"AQID\"");
        assertThat(values.json("{\"a\":[1,2]}", JSON())).isEqualTo("{\"a\":[1,2]}");
        assertThat(values.json((byte) 7, null)).isEqualTo("7");
        assertThat(values.json(List.of(LocalDate.of(2026, 1, 5)), ARRAY(DATE())))
                .isEqualTo("[\"2026-01-05\"]");
        assertThat(values.json(true, null)).isEqualTo("true");
        assertThat(values.json('c', null)).isEqualTo("\"c\"");
    }

    @Test
    void numbersAreRightAligned() {
        assertThat(Values.numeric(DECIMAL(10, 2))).isTrue();
        assertThat(Values.numeric(STRING())).isFalse();
        assertThat(Values.numeric(null)).isFalse();
    }

    @Test
    void cellsMeasureTerminalColumns() {
        assertThat(TableBlock.width("abc")).isEqualTo(3);
        assertThat(TableBlock.width("中文")).isEqualTo(4);
        assertThat(TableBlock.width("한국어")).isEqualTo(6);
        assertThat(TableBlock.width("é")).isEqualTo(1);
        assertThat(TableBlock.width("😀")).isEqualTo(2);
        assertThat(TableBlock.cell("abcdef", 4)).isEqualTo("abc…");
        assertThat(TableBlock.cell("abcd", 4)).isEqualTo("abcd");
        assertThat(TableBlock.cell("a\rb", 0)).isEqualTo("a\\rb");
    }
}
