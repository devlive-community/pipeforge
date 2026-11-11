/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataTypes;
import org.junit.jupiter.api.Test;

class ValueConverterTest {

    private static final TableId TABLE = TableId.of("a", "b");

    private static Object convert(String json, String type) throws Exception {
        JsonNode node = new ObjectMapper().readTree(json);
        return ValueConverter.convert(node, DataTypes.parse(type), TABLE);
    }

    @Test
    void convertsScalars() throws Exception {
        assertThat(convert("true", "BOOLEAN")).isEqualTo(true);
        assertThat(convert("\"FALSE\"", "BOOLEAN")).isEqualTo(false);
        assertThat(convert("-128", "INT8")).isEqualTo((byte) -128);
        assertThat(convert("\"7\"", "INT16")).isEqualTo((short) 7);
        assertThat(convert("2147483647", "INT32")).isEqualTo(Integer.MAX_VALUE);
        assertThat(convert("-9223372036854775808", "INT64")).isEqualTo(Long.MIN_VALUE);
        assertThat(convert("1.5", "FLOAT32")).isEqualTo(1.5f);
        assertThat(convert("\"2.25\"", "FLOAT64")).isEqualTo(2.25);
        assertThat(convert("\"12.5\"", "DECIMAL(5, 2)")).isEqualTo(new BigDecimal("12.50"));
        assertThat(convert("3", "DECIMAL(5, 0)")).isEqualTo(new BigDecimal("3"));
        assertThat(convert("\"x\"", "STRING")).isEqualTo("x");
        assertThat(convert("42", "STRING")).isEqualTo("42");
        assertThat(convert("{\"a\":1}", "JSON")).isEqualTo("{\"a\":1}");
        assertThat(convert("\"AQI=\"", "BYTES")).isEqualTo(new byte[] {1, 2});
        assertThat(convert("\"2026-01-02\"", "DATE")).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(convert("\"10:11:12.5\"", "TIME(3)")).isEqualTo(LocalTime.of(10, 11, 12, 500_000_000));
        assertThat(convert("\"2026-01-02 03:04:05\"", "TIMESTAMP")).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertThat(convert("\"2026-01-02T03:04:05Z\"", "TIMESTAMP_LTZ"))
                .isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(convert("\"2026-01-02T11:04:05+08:00\"", "TIMESTAMP_LTZ"))
                .isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(convert("1000", "TIMESTAMP_LTZ")).isEqualTo(Instant.ofEpochSecond(1));
        assertThat(convert("null", "INT32")).isNull();
    }

    @Test
    void convertsNestedValues() throws Exception {
        assertThat(convert("[1, null, 3]", "ARRAY<INT32>")).isEqualTo(java.util.Arrays.asList(1, null, 3));
        assertThat(convert("{\"1\": \"a\"}", "MAP<INT32, STRING>")).isEqualTo(Map.of(1, "a"));
        Row positional = (Row) convert("[1, \"x\"]", "ROW<a INT32, b STRING>");
        Row named = (Row) convert("{\"b\": \"x\", \"a\": 1}", "ROW<a INT32, b STRING>");
        assertThat(List.of(positional.get(0), positional.get(1))).containsExactly(1, "x");
        assertThat(List.of(named.get(0), named.get(1))).containsExactly(1, "x");
        assertThat(named.table()).isEqualTo(TABLE);
    }

    @Test
    void rejectsValuesThatDoNotFit() {
        assertThatThrownBy(() -> convert("\"yes\"", "BOOLEAN")).hasMessageContaining("expected true or false");
        assertThatThrownBy(() -> convert("128", "INT8")).hasMessageContaining("outside -128 .. 127");
        assertThatThrownBy(() -> convert("\"x\"", "INT32")).hasMessageContaining("expected an integer");
        assertThatThrownBy(() -> convert("1.5", "INT32")).hasMessageContaining("expected an integer");
        assertThatThrownBy(() -> convert("\"x\"", "FLOAT64")).hasMessageContaining("expected a number");
        assertThatThrownBy(() -> convert("\"x\"", "DECIMAL(5, 2)")).hasMessageContaining("expected a decimal");
        assertThatThrownBy(() -> convert("\"1000.0\"", "DECIMAL(5, 2)")).hasMessageContaining("does not fit");
        assertThatThrownBy(() -> convert("\"%%\"", "BYTES")).hasMessageContaining("expected Base64");
        assertThatThrownBy(() -> convert("5", "DATE")).hasMessageContaining("expected a string");
        assertThatThrownBy(() -> convert("\"2026-13-01\"", "DATE")).hasMessageContaining("cannot read");
        assertThatThrownBy(() -> convert("1", "ARRAY<INT32>")).hasMessageContaining("expected an array");
        assertThatThrownBy(() -> convert("[]", "MAP<STRING, INT32>")).hasMessageContaining("expected an object");
        assertThatThrownBy(() -> convert("[1]", "ROW<a INT32, b STRING>")).hasMessageContaining("expected 2 field");
        assertThatThrownBy(() -> convert("1", "ROW<a INT32>")).hasMessageContaining("expected an array or an object");
        assertThatThrownBy(() -> convert("1", "NULL")).hasMessageContaining("only holds null");
    }
}
