/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.type.DataTypes;
import org.junit.jupiter.api.Test;

class ValueTextTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void parsesTextLikeCast() {
        assertThat(ValueText.parse(" 42 ", DataTypes.INT32(), UTC)).isEqualTo(42);
        assertThat(ValueText.parse("2026-11-07", DataTypes.DATE(), UTC)).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(ValueText.parse("yes", DataTypes.BOOLEAN(), UTC)).isEqualTo(true);
        assertThat(ValueText.parse("1.005", DataTypes.DECIMAL(10, 2), UTC)).isEqualTo(new BigDecimal("1.01"));
        assertThat(ValueText.parse(null, DataTypes.INT64(), UTC)).isNull();
        assertThatThrownBy(() -> ValueText.parse("abc", DataTypes.INT32(), UTC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abc");
    }

    @Test
    void rendersValuesAsText() {
        assertThat(ValueText.render(true, DataTypes.BOOLEAN(), UTC)).isEqualTo("TRUE");
        assertThat(ValueText.render(new BigDecimal("1E+3"), DataTypes.DECIMAL(10, 0), UTC))
                .isEqualTo("1000");
        assertThat(ValueText.render(LocalDate.of(2026, 11, 7), DataTypes.DATE(), UTC))
                .isEqualTo("2026-11-07");
        assertThat(ValueText.render(Arrays.asList("a", null), DataTypes.ARRAY(DataTypes.STRING()), UTC))
                .isEqualTo("[a, null]");
        assertThat(ValueText.render(Map.of("k", 1), DataTypes.MAP(DataTypes.STRING(), DataTypes.INT32()), UTC))
                .isEqualTo("{k=1}");
        assertThat(ValueText.render(List.of(), DataTypes.ARRAY(DataTypes.INT32()), UTC))
                .isEqualTo("[]");
        assertThat(ValueText.render(null, DataTypes.STRING(), UTC)).isNull();
        assertThat(ValueText.render(7L, DataTypes.INT64(), UTC)).isEqualTo("7");
    }
}
