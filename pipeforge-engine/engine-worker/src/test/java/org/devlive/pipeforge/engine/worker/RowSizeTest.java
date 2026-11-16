/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.TableId;
import org.junit.jupiter.api.Test;

class RowSizeTest {

    static final TableId T = TableId.of("t");

    @Test
    void countsTheContentOfEveryValue() {
        long empty = RowSize.estimate(GenericRow.of(T));
        assertThat(empty).isEqualTo(32);
        assertThat(RowSize.estimate(GenericRow.of(T, (Object) null))).isEqualTo(40);
        assertThat(RowSize.estimate(GenericRow.of(T, 1L))).isEqualTo(64);
        assertThat(RowSize.value("abcd")).isEqualTo(48);
        assertThat(RowSize.value(new byte[1000])).isEqualTo(1016);
        assertThat(RowSize.value(LocalDate.EPOCH)).isEqualTo(24);
        assertThat(RowSize.value(new BigDecimal("12345678901234567890.5"))).isGreaterThan(48);
        assertThat(RowSize.value(List.of("ab", "cd"))).isEqualTo(24 + 16 + 16 + 2 * 44);
        assertThat(RowSize.value(Map.of("k", 1L))).isEqualTo(48 + 40 + 42 + 24);
        assertThat(RowSize.value(GenericRow.of(T, "abcd"))).isEqualTo(32 + 8 + 48);
    }

    @Test
    void growsWithTheText() {
        String mb = "x".repeat(1024 * 1024);
        assertThat(RowSize.estimate(GenericRow.of(T, 1L, mb))).isBetween(2L * 1024 * 1024, 2L * 1024 * 1024 + 200);
    }
}
