/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.devlive.pipeforge.expressions.Fixtures.ENGINE;
import static org.devlive.pipeforge.expressions.Fixtures.SCHEMA;

import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Type inference and error reporting while planning. */
class AnalyzerTest {

    @ParameterizedTest(name = "{0} : {1}")
    @CsvSource(
            delimiterString = " => ",
            quoteCharacter = '"',
            value = {
                "i + 1 => INT32",
                "i8 + i16 => INT16",
                "i + l => INT64",
                "l * 2 => INT64",
                "i / 2 => DECIMAL(21, 11)",
                "i % 3 => INT32",
                "d + i => DECIMAL(13, 2)",
                "d * d => DECIMAL(21, 4)",
                "d / d => DECIMAL(23, 13)",
                "big * big => DECIMAL(38, 6)",
                "x + i => FLOAT64",
                "f + f => FLOAT32",
                "f + d => FLOAT64",
                "1.5 => DECIMAL(2, 1) NOT NULL",
                "1e3 => FLOAT64 NOT NULL",
                "3000000000 => INT64 NOT NULL",
                "-i => INT32",
                "s || 'x' => STRING",
                "s5 || 'xy' => STRING(7)",
                "i > l => BOOLEAN",
                "id = 1 => BOOLEAN NOT NULL",
                "dt < ts => BOOLEAN",
                "s IS NULL => BOOLEAN NOT NULL",
                "i IS DISTINCT FROM 1 => BOOLEAN NOT NULL",
                "i BETWEEN 1 AND 10 => BOOLEAN",
                "i IN (1, 2, NULL) => BOOLEAN",
                "s LIKE 'h%' => BOOLEAN",
                "s RLIKE '^h' => BOOLEAN",
                "CASE WHEN b THEN i ELSE l END => INT64",
                "CASE WHEN b THEN 'a' ELSE 'bcd' END => STRING(3) NOT NULL",
                "CASE WHEN b THEN 1 END => INT32",
                "IF(b, d, i) => DECIMAL(12, 2)",
                "COALESCE(i, 0) => INT32 NOT NULL",
                "COALESCE(i, l) => INT64",
                "NULLIF(i, 0) => INT32",
                "IFNULL(s, 'n/a') => STRING NOT NULL",
                "CAST(s AS BIGINT) => INT64",
                "CAST(s AS INT64) => INT64",
                "CAST(i AS DECIMAL(10, 2)) => DECIMAL(10, 2)",
                "TRY_CAST(s AS DATE) => DATE",
                "CAST(id AS STRING) => STRING NOT NULL",
                "CAST(ts AS VARCHAR(5)) => STRING(5)",
                "CAST(s AS TIMESTAMP(3)) => TIMESTAMP(3)",
                "CAST(s AS STRING(3)) => STRING(3)",
                "cast(s as string(3)) => STRING(3)",
                "CAST(bin AS BYTES(4)) => BYTES(4)",
                "TRY_CAST(s AS TIMESTAMP_LTZ(3)) => TIMESTAMP_LTZ(3)",
                "CAST(arr AS ARRAY<INT64>) => ARRAY<INT64>",
                "CAST(CAST(arr AS ARRAY<INT64>) AS ARRAY<STRING(4)>) => ARRAY<STRING(4)>",
                "CAST(m AS MAP<STRING(8), DECIMAL(20, 0)>) => MAP<STRING(8), DECIMAL(20, 0)>",
                "CAST(ts AS TIMESTAMP(3) WITH LOCAL TIME ZONE) => TIMESTAMP_LTZ(3)",
                "addr.city => STRING",
                "addr.ZIP => INT32",
                "arr[2] => INT32",
                "m['a'] => INT64",
                "ARRAY[1, 2, 3] => ARRAY<INT32 NOT NULL> NOT NULL",
                "MAP['k', 1] => MAP<STRING(1) NOT NULL, INT32 NOT NULL> NOT NULL",
                "$table => STRING",
                "$rowkind => STRING NOT NULL",
                "DATE '2026-11-07' => DATE NOT NULL",
                "TIMESTAMP '2026-11-07 12:30:00.123' => TIMESTAMP(3) NOT NULL",
                "TIME '12:30:00' => TIME(0) NOT NULL",
                "TIMESTAMP_LTZ '2026-11-07 12:30:00+08:00' => TIMESTAMP_LTZ(0) NOT NULL",
                "NULL => NULL",
                "name => STRING",
                "`Name` => STRING",
            })
    void infersTypes(String expression, String type) {
        assertThat(ENGINE.analyze(expression, SCHEMA).type()).hasToString(type);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiterString = " => ",
            quoteCharacter = '"',
            value = {
                "i + => 1 => 3 => unexpected '+'",
                "'10' > 9 => 1 => 1 => cannot compare STRING(2) with INT32",
                "s + 1 => 1 => 1 => + needs numbers, got STRING; strings are not converted implicitly",
                "missing + 1 => 1 => 1 => column 'missing' does not exist",
                "nme = 'x' => 1 => 1 => did you mean 'name'?",
                "NAME = 'x' => 1 => 1 => column 'NAME' is ambiguous",
                "`nAmE` = 'x' => 1 => 1 => column `nAmE` does not exist",
                "i AND b => 1 => 1 => expected a BOOLEAN condition, got INT32",
                "CASE WHEN b THEN 1 ELSE 'x' END => 1 => 1 => the results have no common type",
                "CAST(arr AS INT) => 1 => 1 => cannot cast ARRAY<INT32> to INT32",
                "CAST(s AS GADGET) => 1 => 11 => unknown type GADGET",
                "CAST(s AS STRING(3)) || nope => 1 => 25 => column 'nope' does not exist",
                "s RLIKE '(unclosed' => 1 => 9 => invalid regular expression",
                "s LIKE 1 => 1 => 8 => LIKE works on strings, got INT32",
                "unknown_fn(1) => 1 => 1 => unknown function UNKNOWN_FN",
                "$nope = 1 => 1 => 1 => unknown pseudo-column $nope",
                "addr.street => 1 => 1 => the ROW has no field 'street'",
                "s.x => 1 => 1 => is a field access, but the value is STRING",
                "arr['x'] => 1 => 5 => an array index must be an integer",
                "i[1] => 1 => 1 => [] needs an ARRAY or a MAP",
                "INTERVAL '5' MINUTE => 1 => 1 => INTERVAL values are not supported yet",
                "DATE '2026-13-01' => 1 => 1 => is not a valid DATE value",
                "123456789012345678901234567890123456789.5 => 1 => 1 => has more than 38 digits",
                "i IN (1, 'a') => 1 => 1 => the values of IN have no common type",
                "m < m => 1 => 1 => can only be compared for equality",
                "IF(b, 1) => 1 => 1 => IF needs 3 arguments",
                "NULLIF(1) => 1 => 1 => NULLIF needs 2 arguments",
                "TIMESTAMP_LTZ '2026-11-07 12:30:00' => 1 => 1 => needs an offset",
                "dt || 'x' => 1 => 1 => || concatenates strings",
                "i BETWEEN SYMMETRIC 1 AND 2 => 1 => 1 => BETWEEN SYMMETRIC is not supported",
            })
    void reportsProblemsWithPositions(String expression, int line, int column, String reason) {
        assertThatThrownBy(() -> ENGINE.analyze(expression, SCHEMA))
                .isInstanceOf(PipeForgeException.class)
                .satisfies(e -> {
                    PipeForgeException error = (PipeForgeException) e;
                    assertThat(error.code()).isEqualTo(ErrorCode.EXPRESSION_INVALID);
                    assertThat(error.params()).containsEntry("line", line).containsEntry("column", column);
                    assertThat(String.valueOf(error.params().get("reason"))).contains(reason);
                });
    }

    @Test
    void mapsPositionsBehindRewrittenLiteralsBack() {
        assertPosition("TIMESTAMP_LTZ '2026-11-07 00:00:00Z' = )", 1, 38);
        assertPosition("timestamp_ltz\t'2026-11-07 00:00:00Z' = )", 1, 38);
    }

    @Test
    void reportsLinesOfMultiLineExpressions() {
        assertPosition("i +\n  'x'", 2, 3);
        assertPosition("b AND\n  missing", 2, 3);
    }

    private static void assertPosition(String expression, int line, int column) {
        assertThatThrownBy(() -> ENGINE.analyze(expression, SCHEMA))
                .satisfies(e -> assertThat(((PipeForgeException) e).params())
                        .containsEntry("line", line)
                        .containsEntry("column", column));
    }
}
