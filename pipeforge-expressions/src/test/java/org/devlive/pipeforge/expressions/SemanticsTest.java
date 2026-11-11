/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.devlive.pipeforge.expressions.Fixtures.eval;
import static org.devlive.pipeforge.expressions.Fixtures.nulls;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Run-time behavior; every case is checked interpreted and compiled. */
class SemanticsTest {

    private static String bool(Object value) {
        return value == null ? "NULL" : value.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /** SQL three-valued logic: every combination of TRUE, FALSE and NULL. */
    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
        "TRUE,  TRUE,  TRUE,  TRUE",
        "TRUE,  FALSE, FALSE, TRUE",
        "TRUE,  NULL,  NULL,  TRUE",
        "FALSE, TRUE,  FALSE, TRUE",
        "FALSE, FALSE, FALSE, FALSE",
        "FALSE, NULL,  FALSE, NULL",
        "NULL,  TRUE,  NULL,  TRUE",
        "NULL,  FALSE, FALSE, NULL",
        "NULL,  NULL,  NULL,  NULL",
    })
    void andOrFollowThreeValuedLogic(String a, String b, String and, String or) {
        String x = "CAST(" + a + " AS BOOLEAN)";
        String y = "CAST(" + b + " AS BOOLEAN)";
        assertThat(bool(eval(x + " AND " + y))).isEqualTo(and);
        assertThat(bool(eval(x + " OR " + y))).isEqualTo(or);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "TRUE,  FALSE, TRUE,  FALSE, FALSE, TRUE",
        "FALSE, TRUE,  FALSE, TRUE,  TRUE,  FALSE",
        "NULL,  NULL,  FALSE, FALSE, TRUE,  TRUE",
    })
    void notAndIsPredicates(
            String value, String not, String isTrue, String isFalse, String isNotTrue, String isNotFalse) {
        String v = "CAST(" + value + " AS BOOLEAN)";
        assertThat(bool(eval("NOT " + v))).isEqualTo(not);
        assertThat(bool(eval(v + " IS TRUE"))).isEqualTo(isTrue);
        assertThat(bool(eval(v + " IS FALSE"))).isEqualTo(isFalse);
        assertThat(bool(eval(v + " IS NOT TRUE"))).isEqualTo(isNotTrue);
        assertThat(bool(eval(v + " IS NOT FALSE"))).isEqualTo(isNotFalse);
    }

    @Test
    void comparisonsWithNullAreUnknownUnlessDistinct() {
        assertThat(eval("i = 32")).isEqualTo(true);
        assertThat(eval("i = NULL")).isNull();
        assertThat(eval("i <> 1", nulls())).isNull();
        assertThat(eval("i IS DISTINCT FROM NULL")).isEqualTo(true);
        assertThat(eval("i IS DISTINCT FROM NULL", nulls())).isEqualTo(false);
        assertThat(eval("i IS NOT DISTINCT FROM 32")).isEqualTo(true);
        assertThat(eval("s IS NULL", nulls())).isEqualTo(true);
        assertThat(eval("s IS NOT NULL")).isEqualTo(true);
    }

    @Test
    void comparesAcrossNumericAndTemporalTypes() {
        assertThat(eval("i < l")).isEqualTo(true);
        assertThat(eval("d = 12.5")).isEqualTo(true);
        assertThat(eval("x > d")).isEqualTo(false);
        assertThat(eval("i8 >= 8 AND i16 <= 16")).isEqualTo(true);
        assertThat(eval("dt < ts")).isEqualTo(true);
        assertThat(eval("dt = DATE '2026-11-07'")).isEqualTo(true);
        assertThat(eval("s > 'abc'")).isEqualTo(true);
        assertThat(eval("bin = CAST('hi' AS VARBINARY)")).isEqualTo(true);
        assertThat(eval("arr = ARRAY[10, 20, 30]")).isEqualTo(true);
        assertThat(eval("CAST(0.0 AS DOUBLE) = -CAST(0.0 AS DOUBLE)")).isEqualTo(true);
    }

    @Test
    void inBetweenAndLists() {
        assertThat(eval("i IN (1, 32)")).isEqualTo(true);
        assertThat(eval("i IN (1, 2)")).isEqualTo(false);
        assertThat(eval("i IN (1, NULL)")).isNull();
        assertThat(eval("i IN (32, NULL)")).isEqualTo(true);
        assertThat(eval("i NOT IN (1, NULL)")).isNull();
        assertThat(eval("i IN (1, 2)", nulls())).isNull();
        assertThat(eval("i BETWEEN 30 AND 40")).isEqualTo(true);
        assertThat(eval("i NOT BETWEEN 30 AND 40")).isEqualTo(false);
        assertThat(eval("d BETWEEN 1 AND 12.5")).isEqualTo(true);
    }

    @Test
    void likeAndRegularExpressions() {
        assertThat(eval("s LIKE 'he%'")).isEqualTo(true);
        assertThat(eval("s LIKE 'h_llo'")).isEqualTo(true);
        assertThat(eval("s LIKE 'H%'")).isEqualTo(false);
        assertThat(eval("s NOT LIKE '%z%'")).isEqualTo(true);
        assertThat(eval("'50%' LIKE '50!%' ESCAPE '!'")).isEqualTo(true);
        assertThat(eval("'500' LIKE '50!%' ESCAPE '!'")).isEqualTo(false);
        assertThat(eval("'a.b' LIKE 'a.b'")).isEqualTo(true);
        assertThat(eval("'axb' LIKE 'a.b'")).isEqualTo(false);
        assertThat(eval("'x' LIKE 'x!' ESCAPE '!'"))
                .isEqualTo("ERROR: the LIKE pattern 'x!' ends with the escape character");
        assertThat(eval("'x' LIKE 'x' ESCAPE '!!'"))
                .isEqualTo("ERROR: the LIKE escape must be a single character, got '!!'");
        assertThat(eval("s LIKE 'h%'", nulls())).isNull();
        assertThat(eval("s RLIKE 'l+o$'")).isEqualTo(true);
        assertThat(eval("s NOT RLIKE '^x'")).isEqualTo(true);
        assertThat(eval("s RLIKE s5")).isEqualTo(false);
        assertThat(eval("s RLIKE CAST(s5 || '(' AS STRING)"))
                .asString()
                .startsWith("ERROR: invalid regular expression");
    }

    @Test
    void conditionalExpressionsAreLazy() {
        assertThat(eval("CASE WHEN i > 100 THEN 'big' WHEN i > 10 THEN 'medium' ELSE 'small' END"))
                .isEqualTo("medium");
        assertThat(eval("CASE WHEN i > 100 THEN 'big' END")).isNull();
        assertThat(eval("CASE i WHEN 32 THEN 'x' ELSE 'y' END")).isEqualTo("x");
        assertThat(eval("CASE WHEN i8 = 0 THEN 0 ELSE l / (i8 - 8) END"))
                .asString()
                .startsWith("ERROR: division by zero");
        assertThat((BigDecimal) eval("CASE WHEN i8 = 8 THEN 0 ELSE l / (i8 - 8) END"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(eval("IF(b, 'yes', 'no')")).isEqualTo("yes");
        assertThat(eval("IF(b, 'yes', 'no')", nulls())).isEqualTo("no");
        assertThat(eval("COALESCE(s, 'fallback')", nulls())).isEqualTo("fallback");
        assertThat((BigDecimal) eval("COALESCE(d, 1 / 0)")).isEqualByComparingTo("12.5");
        assertThat(eval("COALESCE(i, l)")).isEqualTo(32L);
        assertThat(eval("IFNULL(s, 'n/a')", nulls())).isEqualTo("n/a");
        assertThat(eval("NULLIF(i, 32)")).isNull();
        assertThat(eval("NULLIF(i, 1)")).isEqualTo(32);
        assertThat(eval("b AND 1 / (i - 32) > 0")).asString().startsWith("ERROR: division by zero");
        assertThat(eval("NOT b AND 1 / (i - 32) > 0")).isEqualTo(false);
        assertThat(eval("b OR 1 / (i - 32) > 0")).isEqualTo(true);
    }

    @Test
    void integerArithmeticStaysExactAndChecksOverflow() {
        assertThat(eval("i + 1")).isEqualTo(33);
        assertThat(eval("i8 + i8")).isEqualTo((byte) 16);
        assertThat(eval("l - 100")).isEqualTo(-36L);
        assertThat(eval("i % 5")).isEqualTo(2);
        assertThat(eval("-i")).isEqualTo(-32);
        assertThat(eval("2147483647 + i")).isEqualTo("ERROR: numeric overflow: the result does not fit into INT32");
        assertThat(eval("CAST(9223372036854775807 AS BIGINT) + 1"))
                .isEqualTo("ERROR: numeric overflow: the result does not fit into INT64");
        assertThat(eval("CAST(127 AS TINYINT) + CAST(1 AS TINYINT)"))
                .isEqualTo("ERROR: numeric overflow: the result does not fit into INT8");
        assertThat(eval("i % 0")).isEqualTo("ERROR: division by zero");
        assertThat(eval("-CAST(-9223372036854775808 AS BIGINT)")).asString().startsWith("ERROR: numeric overflow");
    }

    @Test
    void integerDivisionIsDecimal() {
        assertThat(eval("7 / 2")).isEqualTo(new BigDecimal("3.50000000000"));
        assertThat(eval("i / 0")).isEqualTo("ERROR: division by zero");
        assertThat(eval("CAST(1 AS DOUBLE) / 0")).isEqualTo("ERROR: division by zero");
    }

    @Test
    void decimalArithmeticFollowsPrecisionRules() {
        assertThat(eval("d + 0.005")).isEqualTo(new BigDecimal("12.505"));
        assertThat(eval("d * d")).isEqualTo(new BigDecimal("156.2500"));
        assertThat(eval("d / 3")).isEqualTo(new BigDecimal("4.1666666666667"));
        assertThat(eval("d % 5")).isEqualTo(new BigDecimal("2.50"));
        assertThat(eval("CAST(99999999.99 AS DECIMAL(10, 2)) * CAST(1E30 AS DECIMAL(38, 0))"))
                .asString()
                .startsWith("ERROR: value ")
                .contains("is out of range of DECIMAL(38, 2)");
        assertThat(eval("x + i")).isEqualTo(34.25);
        assertThat(eval("f * f")).isEqualTo(2.25f);
        assertThat(eval("CAST(1E308 AS DOUBLE) * 10"))
                .isEqualTo("ERROR: numeric overflow: the result does not fit into FLOAT64");
    }

    @Test
    void stringsConcatenate() {
        assertThat(eval("s || ' ' || s5")).isEqualTo("hello abc");
        assertThat(eval("s || NULL")).isNull();
    }

    @Test
    void castsConvertOrFail() {
        assertThat(eval("CAST('42' AS INT)")).isEqualTo(42);
        assertThat(eval("CAST(' 7.6 ' AS BIGINT)")).isEqualTo(8L);
        assertThat(eval("CAST('abc' AS BIGINT)")).isEqualTo("ERROR: cannot cast 'abc' to INT64");
        assertThat(eval("TRY_CAST('abc' AS BIGINT)")).isNull();
        assertThat(eval("CAST(300 AS TINYINT)"))
                .isEqualTo("ERROR: numeric overflow: the result does not fit into INT8");
        assertThat(eval("CAST(d AS INT)")).isEqualTo(13);
        assertThat(eval("CAST(x AS DECIMAL(5, 1))")).isEqualTo(new BigDecimal("2.3"));
        assertThat(eval("CAST(12345.6 AS DECIMAL(4, 1))"))
                .isEqualTo("ERROR: value 12345.6 is out of range of DECIMAL(4, 1)");
        assertThat(eval("CAST(d AS STRING)")).isEqualTo("12.50");
        assertThat(eval("CAST('abcdef' AS STRING(3))")).isEqualTo("abc");
        assertThat(eval("CAST('abcdef' AS VARCHAR(3))")).isEqualTo("abc");
        assertThat(eval("CAST('2026-11-07 12:30:00+08:00' AS TIMESTAMP_LTZ(3))"))
                .isEqualTo(java.time.Instant.parse("2026-11-07T04:30:00Z"));
        assertThat(eval("CAST(ARRAY[1, 2] AS ARRAY<INT64>)")).isEqualTo(java.util.List.of(1L, 2L));
        assertThat(eval("CAST(x AS STRING)")).isEqualTo("2.25");
        assertThat(eval("CAST(b AS STRING)")).isEqualTo("true");
        assertThat(eval("CAST('yes' AS BOOLEAN)")).isEqualTo(true);
        assertThat(eval("CAST('maybe' AS BOOLEAN)")).isEqualTo("ERROR: cannot cast 'maybe' to BOOLEAN");
        assertThat(eval("CAST(0 AS BOOLEAN)")).isEqualTo(false);
        assertThat(eval("CAST(b AS INT)")).isEqualTo(1);
        assertThat(eval("CAST(s AS VARCHAR(3))")).isEqualTo("hel");
        assertThat(eval("CAST(bin AS STRING)")).isEqualTo("hi");
        assertThat(eval("CAST('nan' AS DOUBLE)")).isEqualTo("ERROR: cannot cast 'nan' to FLOAT64");
    }

    @Test
    void castsDatesAndTimesInTheJobTimeZone() {
        assertThat(eval("CAST('2026-11-07' AS DATE)")).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(eval("CAST('2026-11-07 08:00:01.5' AS TIMESTAMP(3))"))
                .isEqualTo(LocalDateTime.of(2026, 11, 7, 8, 0, 1, 500_000_000));
        assertThat(eval("CAST('2026-11-07' AS TIMESTAMP)")).isEqualTo(LocalDateTime.of(2026, 11, 7, 0, 0));
        assertThat(eval("CAST(ts AS TIMESTAMP(0))")).isEqualTo(LocalDateTime.of(2026, 11, 7, 12, 30));
        assertThat(eval("CAST(ts AS DATE)")).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(eval("CAST(ts AS TIME)")).isEqualTo(LocalTime.of(12, 30));
        assertThat(eval("CAST(ts AS STRING)")).isEqualTo("2026-11-07 12:30:00.123");
        assertThat(eval("CAST(tm AS STRING)")).isEqualTo("12:30:00");
        // Asia/Shanghai is UTC+8: 04:30Z is 12:30 local.
        assertThat(eval("CAST(ltz AS TIMESTAMP(3))")).isEqualTo(LocalDateTime.of(2026, 11, 7, 12, 30));
        assertThat(eval("CAST(ltz AS STRING)")).isEqualTo("2026-11-07 12:30:00+08:00");
        assertThat(eval("CAST(ts AS TIMESTAMP(3) WITH LOCAL TIME ZONE)"))
                .isEqualTo(Instant.parse("2026-11-07T04:30:00.123Z"));
        assertThat(eval("CAST(ts AS TIMESTAMP_LTZ)")).isEqualTo(Instant.parse("2026-11-07T04:30:00.123Z"));
        assertThat(eval("CAST('2026-11-07 12:30:00' AS TIMESTAMP(0) WITH LOCAL TIME ZONE)"))
                .isEqualTo(Instant.parse("2026-11-07T04:30:00Z"));
        assertThat(eval("CAST('2026-11-07T12:30:00+0100' AS TIMESTAMP(0) WITH LOCAL TIME ZONE)"))
                .isEqualTo(Instant.parse("2026-11-07T11:30:00Z"));
        assertThat(eval("ltz = TIMESTAMP_LTZ '2026-11-07 12:30:00+08:00'")).isEqualTo(true);
        assertThat(eval("dt = CAST(ltz AS DATE)")).isEqualTo(true);
        assertThat(eval("CAST('2026-02-30' AS DATE)")).isEqualTo("ERROR: cannot cast '2026-02-30' to DATE");
    }

    @Test
    void readsNestedValuesAndMetadata() {
        assertThat(eval("addr.city")).isEqualTo("Berlin");
        assertThat(eval("addr.zip + 1")).isEqualTo(10116);
        assertThat(eval("addr.city", nulls())).isNull();
        assertThat(eval("arr[1]")).isEqualTo(10);
        assertThat(eval("arr[4]")).isNull();
        assertThat(eval("arr[0]")).isNull();
        assertThat(eval("m['b']")).isEqualTo(2L);
        assertThat(eval("m['zz']")).isNull();
        assertThat(eval("$table")).isEqualTo("orders");
        assertThat(eval("$db || '.' || $schema")).isEqualTo("shop.public");
        assertThat(eval("$rowkind")).isEqualTo("UPDATE_AFTER");
        assertThat(eval("`Name` || name")).isEqualTo("Upperlower");
    }

    @Test
    void buildsArraysAndMaps() {
        assertThat(eval("ARRAY[i, 2, NULL]")).isEqualTo(java.util.Arrays.asList(32, 2, null));
        assertThat(eval("MAP['a', i, 'b', 2]")).isEqualTo(Map.of("a", 32, "b", 2));
        assertThat(eval("ARRAY[1, 2][2]")).isEqualTo(2);
        assertThat(eval("MAP[s, 1]['hello']")).isEqualTo(1);
        assertThat(eval("MAP[s, 1]", nulls())).isEqualTo("ERROR: map keys must not be NULL");
        assertThat(eval("CAST(ARRAY[1, 2] AS BIGINT ARRAY)")).isEqualTo(List.of(1L, 2L));
    }
}
