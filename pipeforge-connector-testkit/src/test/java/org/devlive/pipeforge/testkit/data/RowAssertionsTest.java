/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.RowKind;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

class RowAssertionsTest {

    private static Row row(Object... values) {
        return GenericRow.of(TestData.TABLE, values);
    }

    @Test
    void comparesValuesByMeaningInAnyOrder() {
        Map<String, Integer> forward = new LinkedHashMap<>();
        forward.put("a", 1);
        forward.put("b", 2);
        Map<String, Integer> backward = new LinkedHashMap<>();
        backward.put("b", 2);
        backward.put("a", 1);

        RowAssertions.assertSameRows(
                "rows",
                List.of(row(1L, new BigDecimal("1.50"), new byte[] {1, 2}, forward), row(2L, null, List.of(1, 2), "x")),
                List.of(
                        row(2L, null, List.of(1, 2), "x"),
                        row(1L, new BigDecimal("1.5"), new byte[] {1, 2}, backward)));
        assertThat(RowAssertions.canonical(row(new BigDecimal("0.000"))))
                .isEqualTo(RowAssertions.canonical(row(BigDecimal.ZERO)));
    }

    @Test
    void distinguishesKindsTypesAndFloatingPointValues() {
        assertThat(RowAssertions.canonical(row(1))).isNotEqualTo(RowAssertions.canonical(row(1L)));
        assertThat(RowAssertions.canonical(row(0.0))).isNotEqualTo(RowAssertions.canonical(row(-0.0)));
        assertThat(RowAssertions.canonical(row(1.0f))).isNotEqualTo(RowAssertions.canonical(row(1.0)));
        assertThat(RowAssertions.canonical(GenericRow.ofKind(TestData.TABLE, RowKind.DELETE, 1)))
                .startsWith("DELETE ");
        assertThat(RowAssertions.canonical(row("it's"))).isEqualTo("('it''s')");
        assertThat(RowAssertions.canonical(row(row(1, "a")))).isEqualTo("((I1, 'a'))");
        assertThat(RowAssertions.canonical(row(java.time.LocalDate.of(2026, 1, 2))))
                .isEqualTo("(LocalDate:2026-01-02)");
    }

    @Test
    void reportsMissingAndUnexpectedRowsWithCounts() {
        assertThatThrownBy(() -> RowAssertions.assertSameRows(
                        "rows written", List.of(row(1L), row(2L), row(2L)), List.of(row(1L), row(3L))))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessage(
                        """
                        rows written: expected 3 row(s) but got 2
                          missing (2):
                            (L2) ×2
                          unexpected (1):
                            (L3)""");
    }

    @Test
    void shortensLongReports() {
        List<Row> expected = new ArrayList<>();
        for (long i = 0; i < 15; i++) {
            expected.add(row(i, "y".repeat(300)));
        }
        assertThatThrownBy(() -> RowAssertions.assertSameRows("rows", expected, List.of()))
                .hasMessageContaining("… and 5 more")
                .hasMessageContaining("…\n");
    }
}
