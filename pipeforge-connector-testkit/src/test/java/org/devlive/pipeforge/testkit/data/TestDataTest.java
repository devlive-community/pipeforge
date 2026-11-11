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
import java.time.LocalTime;
import java.util.List;
import java.util.Random;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.junit.jupiter.api.Test;

class TestDataTest {

    @Test
    void coversEveryLogicalTypeExceptNull() {
        assertThat(TestData.TYPES.stream().map(DataType::root).distinct())
                .containsExactlyInAnyOrder(java.util.Arrays.stream(TypeRoot.values())
                        .filter(r -> r != TypeRoot.NULL)
                        .toArray(TypeRoot[]::new));
    }

    @Test
    void typeTablesStartWithNullAndHoldValuesOfTheirType() {
        for (DataType type : TestData.TYPES) {
            TestTable table = TestData.typeTable(type);
            assertThat(table.rows().get(0).get(1)).as(type.toString()).isNull();
            assertThat(table.rows()).hasSizeGreaterThan(5);
            for (Row row : table.rows().subList(1, table.rows().size())) {
                Object value = row.get(1);
                assertThat(value).as(type.toString()).isNotNull();
                assertThat(type.root().javaClass().isInstance(value))
                        .as("%s holds %s", type, value.getClass())
                        .isTrue();
            }
            assertThat(table.rows().stream().map(r -> (Long) r.get(0)).distinct())
                    .hasSize(table.rows().size());
        }
    }

    @Test
    void boundariesAreTheExtremesOfTheType() {
        assertThat(TestData.boundaries(DataTypes.DECIMAL(5, 2)))
                .contains(new BigDecimal("999.99"), new BigDecimal("-999.99"), new BigDecimal("0.01"));
        assertThat(TestData.boundaries(DataTypes.STRING(10))).contains("x".repeat(10));
        assertThat(TestData.boundaries(DataTypes.STRING())).contains("x".repeat(TestData.LONG_STRING));
        assertThat(TestData.boundaries(DataTypes.TIME(0))).contains(LocalTime.of(23, 59, 59));
        assertThat(TestData.boundaries(DataTypes.NULL())).isEmpty();
        assertThatThrownBy(() -> TestData.value(DataTypes.NULL(), new Random(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatedRowsAreRepeatableAndNumberedFromZero() {
        TestTable first = TestData.rows("a", TestData.wideTable(), 50, 42);
        TestTable second = TestData.rows("b", TestData.wideTable(), 50, 42);

        RowAssertions.assertSameRows("same seed", first.rows(), second.rows());
        assertThat(first.rows().stream().map(r -> (Long) r.get(0)).toList())
                .isEqualTo(java.util.stream.LongStream.range(0, 50).boxed().toList());
        assertThat(first.rows().stream().anyMatch(r -> r.get(1) == null)).isTrue();
        assertThat(TestData.rows("n", TestData.table(DataTypes.NULL()), 3, 1).rows())
                .allSatisfy(r -> assertThat(r.get(1)).isNull());
        assertThat(first.withRows("c", List.of()).rows()).isEmpty();
    }

    @Test
    void namesTypesForTestNames() {
        assertThat(TestData.name(DataTypes.DECIMAL(10, 2).notNull())).isEqualTo("DECIMAL(10, 2)");
    }
}
