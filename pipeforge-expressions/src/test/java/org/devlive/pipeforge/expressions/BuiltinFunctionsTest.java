/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.expressions.function.FunctionDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Boundary values and NULL behavior of the built-in functions. */
class BuiltinFunctionsTest {

    private static final ExpressionEngine ENGINE = new ExpressionEngine();
    private static final InputSchema EMPTY = InputSchema.of(List.of());

    private static String run(String expression) {
        return FunctionCatalogTest.run(expression, true);
    }

    @Test
    void nullArgumentsGiveNullForNullPropagatingFunctions() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (FunctionDefinition function : ENGINE.functions().all()) {
            if (!function.nullOnNull() || KEYWORD_SYNTAX.contains(function.name())) {
                continue;
            }
            for (FunctionDefinition.Signature signature : function.signatures()) {
                if (signature.parameters().isEmpty()) {
                    continue;
                }
                List<String> args = new ArrayList<>();
                for (DataType parameter : signature.parameters()) {
                    args.add("CAST(NULL AS " + castName(parameter) + ")");
                }
                if (signature.variadic()) {
                    args.add(args.get(args.size() - 1));
                }
                String expression = function.name() + "(" + String.join(", ", args) + ")";
                String result = run(expression);
                checked++;
                if (!result.equals("NULL")) {
                    problems.add(expression + " gives " + result);
                }
            }
        }
        assertThat(problems).isEmpty();
        assertThat(checked).isGreaterThan(80);
        // Functions that are only reachable through their keyword syntax.
        assertThat(run("TRIM(BOTH CAST(NULL AS STRING) FROM 'a')")).isEqualTo("NULL");
        assertThat(run("TRIM(LEADING 'x' FROM CAST(NULL AS STRING))")).isEqualTo("NULL");
        assertThat(run("POSITION(CAST(NULL AS STRING) IN 'abc')")).isEqualTo("NULL");
        assertThat(run("TIMESTAMPDIFF(DAY, CAST(NULL AS TIMESTAMP), TIMESTAMP '2026-11-07 00:00:00')"))
                .isEqualTo("NULL");
        assertThat(run("EXTRACT(YEAR FROM CAST(NULL AS DATE))")).isEqualTo("NULL");
    }

    /** Overloads that SQL only reaches through keyword syntax (TRIM ... FROM, POSITION ... IN). */
    private static final java.util.Set<String> KEYWORD_SYNTAX = java.util.Set.of("POSITION", "TIMESTAMPDIFF", "TRIM");

    private static String castName(DataType type) {
        return switch (type.root()) {
            case TIMESTAMP_LTZ -> "TIMESTAMP(3) WITH LOCAL TIME ZONE";
            case BYTES -> "VARBINARY";
            case ARRAY -> type.elementType().nullable() + " ARRAY";
            default -> type.nullable().toString();
        };
    }

    @Test
    void functionsThatHandleNullThemselves() {
        assertThat(run("IS_NUMERIC(NULL)")).isEqualTo("FALSE");
        assertThat(run("IS_DATE(NULL)")).isEqualTo("FALSE");
        assertThat(run("CONCAT_WS(NULL, 'a')")).isEqualTo("NULL");
        assertThat(run("CONCAT_WS(',', NULL, NULL)")).isEqualTo("");
        assertThat(run("GREATEST(NULL, NULL)")).isEqualTo("NULL");
        assertThat(run("FORMAT(NULL, 1)")).isEqualTo("NULL");
        assertThat(run("FORMAT('%s', NULL)")).isEqualTo("null");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiterString = " => ",
            quoteCharacter = '"',
            value = {
                // numeric boundaries
                "ABS(CAST(-2147483648 AS INT)) => ERROR: numeric overflow: ABS(-2147483648) does not fit into INT32",
                "ABS(CAST(-9223372036854775808 AS BIGINT)) => ERROR: numeric overflow: ABS(-9223372036854775808) does not fit into INT64",
                "ABS(-2147483647) => 2147483647",
                "ROUND(9223372036854775807, -1) => ERROR: numeric overflow: ROUND(9223372036854775807, -1) does not fit into INT64",
                "ROUND(CAST(99.99 AS DECIMAL(4, 2))) => ERROR: value 100.00 is out of range of DECIMAL(4, 2)",
                "ROUND(-2.5) => -3.0",
                "ROUND(CAST(2.5 AS DOUBLE)) => 3.0",
                "ROUND(CAST(1.005 AS DOUBLE), 2) => 1.01",
                "CEIL(CAST(-0.5 AS DOUBLE)) => -0.0",
                "TRUNCATE(CAST(-1.99 AS DOUBLE), 0) => -1.0",
                "DIV(CAST(-9223372036854775808 AS BIGINT), -1) => ERROR: numeric overflow: the result does not fit into INT64",
                "DIV(7.5, 2) => 3",
                "POWER(10, 400) => ERROR: numeric overflow in POWER",
                "POWER(-8, 0.5) => ERROR: POWER is undefined for these arguments",
                "EXP(1000) => ERROR: numeric overflow in EXP",
                "LOG(-2, 8) => ERROR: LOG needs a positive number, got -2.0",
                "BIN(-1) => 1111111111111111111111111111111111111111111111111111111111111111",
                "HEX(-1) => FFFFFFFFFFFFFFFF",
                "CONV('10', 1, 10) => ERROR: bases must be between 2 and 36, got 1",
                "CONV('-ff', 16, 10) => -255",
                "CONV('z', 36, 10) => 35",
                "SIGN(CAST(-0.0 AS DOUBLE)) => 0",
                // strings: empty values, characters outside the BMP, limits
                "LENGTH('') => 0",
                "LENGTH('a😀b') => 3",
                "OCTET_LENGTH('😀') => 4",
                "REVERSE('a😀b') => b😀a",
                "SUBSTRING('a😀b', 2, 1) => 😀",
                "SUBSTRING('abc', 5) => ",
                "SUBSTRING('abc', 2, 0) => ",
                "SUBSTRING('abc', 1, -1) => ERROR: SUBSTRING length must not be negative, got -1",
                "LEFT('abc', 10) => abc",
                "LEFT('abc', -1) => ERROR: LEFT needs a non-negative length, got -1",
                "RIGHT('abc', 0) => ",
                "RIGHT('a😀', 1) => 😀",
                "LPAD('ab', 5, '') => ab",
                "LPAD('ab', 7, 'xyz') => xyzxyab",
                "RPAD('ab', -1) => ERROR: the padded length must be between 0 and 1048576, got -1",
                "REPEAT('ab', 0) => ",
                "REPEAT('ab', -1) => ERROR: REPEAT needs a non-negative count, got -1",
                "REPEAT('abcd', 300000) => ERROR: REPEAT would build more than 1048576 characters",
                "POSITION('' IN 'abc') => 1",
                "POSITION('😀b' IN 'a😀b') => 2",
                "REPLACE('abc', '', 'x') => abc",
                "TRANSLATE('a😀', '😀', 'b') => ab",
                "INITCAP('o''neil mc-donald') => O'Neil Mc-Donald",
                "TRIM(TRAILING 'x' FROM 'xxaxx') => xxa",
                "LTRIM('  a') => a",
                "RTRIM('a  ') => a",
                "SPLIT('', ',') => []",
                "SPLIT('a,,b', ',') => [a, , b]",
                "SPLIT('ab', '') => [a, b]",
                "SPLIT_PART('a,b', ',', 0) => ERROR: SPLIT_PART index must be at least 1, got 0",
                "ASCII('') => 0",
                "CHR(128512) => 😀",
                "CHR(-1) => ERROR: CHR needs a valid code point, got -1",
                "REGEXP_EXTRACT('abc', 'b', 2) => ERROR: the regular expression has no group 2",
                "REGEXP_REPLACE('abc', 'b', '$2') => ERROR: invalid replacement '$2': n > number of groups: 2",
                "REGEXP_LIKE('abc', '(') => ERROR: invalid regular expression '(': missing closing )",
                "FORMAT('%d', 'x') => ERROR: invalid FORMAT pattern '%d': d != java.lang.String",
                "CONCAT('a') => a",
                // dates: month ends, leap years, units, patterns, zones
                "DATE_ADD(DATE '2024-02-29', 1, 'year') => 2025-02-28",
                "DATE_ADD(DATE '2026-11-07', 1, 'quarter') => 2027-02-07",
                "DATE_ADD(DATE '2026-11-07', 1, 'hour') => ERROR: cannot add hour to a DATE; cast it to TIMESTAMP first",
                "DATE_ADD(TIMESTAMP '2026-11-07 00:00:00', 1, 'fortnight') => ERROR: unknown time unit 'fortnight'; use SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, QUARTER or YEAR",
                "DATE_ADD(TIMESTAMP '2026-11-07 00:00:00', 2, 'Weeks') => 2026-11-21 00:00:00",
                "DATE_SUB(DATE '2026-03-31', 1, 'month') => 2026-02-28",
                "DATE_ADD(TIMESTAMP_LTZ '2026-11-07 00:00:00Z', 1, 'day') => 2026-11-08 08:00:00+08:00",
                "DATEDIFF(DATE '2026-01-01', DATE '2026-12-31') => -364",
                "DATEDIFF(TIMESTAMP '2026-11-08 00:00:01', TIMESTAMP '2026-11-07 23:59:59') => 1",
                "TIMESTAMPDIFF(QUARTER, DATE '2026-01-01', DATE '2026-12-31') => 3",
                "TIMESTAMPDIFF(SECOND, TIMESTAMP '2026-11-07 00:00:00', TIMESTAMP '2026-11-06 23:59:00') => -60",
                "TIMESTAMPDIFF(DAY, TIMESTAMP_LTZ '2026-11-07 15:00:00Z', TIMESTAMP_LTZ '2026-11-08 17:00:00Z') => 1",
                "DATE_TRUNC('quarter', DATE '2026-11-07') => 2026-10-01",
                "DATE_TRUNC('year', TIMESTAMP '2026-11-07 12:30:00') => 2026-01-01 00:00:00",
                "DATE_TRUNC('hour', TIMESTAMP '2026-11-07 12:30:45') => 2026-11-07 12:00:00",
                "DATE_TRUNC('day', TIMESTAMP_LTZ '2026-11-07 20:00:00Z') => 2026-11-08 00:00:00+08:00",
                "DATE_TRUNC('decade', DATE '2026-11-07') => ERROR: unknown time unit 'decade'",
                "LAST_DAY(DATE '2024-02-01') => 2024-02-29",
                "LAST_DAY(TIMESTAMP '2026-12-15 10:00:00') => 2026-12-31",
                "WEEK(DATE '2026-01-01') => 1",
                "WEEK(DATE '2027-01-01') => 53",
                "DAYOFWEEK(DATE '2026-11-08') => 1",
                "EXTRACT(DOY FROM DATE '2024-12-31') => 366",
                "EXTRACT(HOUR FROM TIME '23:15:00') => 23",
                "MINUTE(TIMESTAMP_LTZ '2026-11-07 00:00:00Z') => 0",
                "CONVERT_TZ(TIMESTAMP '2026-03-29 01:30:00', 'UTC', 'Europe/Berlin') => 2026-03-29 03:30:00",
                "CONVERT_TZ(TIMESTAMP '2026-11-07 00:00:00', 'Mars/Olympus', 'UTC') => ERROR: unknown time zone 'Mars/Olympus'",
                "DATE_FORMAT(DATE '2026-11-07', 'EEE dd MMM') => Sat 07 Nov",
                "DATE_FORMAT(TIMESTAMP_LTZ '2026-11-07 00:00:00Z', 'yyyy-MM-dd HH:mm XXX') => 2026-11-07 08:00 +08:00",
                "DATE_FORMAT(DATE '2026-11-07', 'yyyy qqqqqq') => ERROR: invalid date pattern 'yyyy qqqqqq': Too many pattern letters: q",
                "DATE_FORMAT(DATE '2026-11-07', 'HH:mm') => 00:00",
                "DATE_FORMAT(TIMESTAMP '2026-11-07 00:00:00', 'VV') => ERROR: cannot format with 'VV': Unable to extract ZoneId from temporal 2026-11-07T00:00",
                "TO_DATE('2024-02-29', 'yyyy-MM-dd') => 2024-02-29",
                "TO_DATE('2026-11-31', 'yyyy-MM-dd') => ERROR: '2026-11-31' does not match the pattern 'yyyy-MM-dd'",
                "TO_TIMESTAMP('20261107', 'yyyyMMdd') => 2026-11-07 00:00:00",
                "TO_TIMESTAMP('yesterday') => ERROR: 'yesterday' is not a timestamp (yyyy-MM-dd HH:mm:ss)",
                "TO_TIMESTAMP_LTZ('07/11/2026 08:15', 'dd/MM/yyyy HH:mm') => 2026-11-07 08:15:00+08:00",
                "TO_TIMESTAMP_LTZ('soon') => ERROR: 'soon' is not a timestamp",
                "UNIX_TIMESTAMP(TIMESTAMP '1970-01-01 08:00:00') => 0",
                "FROM_UNIXTIME(1794025800) = NOW() => FALSE",
                "CURRENT_DATE = DATE '2026-11-07' => TRUE",
                "RUN_START_TIME() < NOW() => TRUE",
                "DAY(DATE '2026-11-30') + day(DATE '2026-11-01') => 31",
                "TYPEOF(GREATEST(1, 2.5)) => DECIMAL(11, 1)",
                "GREATEST(TIMESTAMP '2026-11-07 00:00:00', DATE '2026-11-08') => 2026-11-08 00:00:00",
                "MOD(10, 3) + MOD(-10, 3) => 0",
            })
    void boundaryValues(String expression, String expected) {
        assertThat(run(expression)).isEqualTo(expected == null ? "" : expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiterString = " => ",
            quoteCharacter = '"',
            value = {
                "UPPER(1) => UPPER does not accept (INT32); expected UPPER(STRING)",
                "GREATEST(1, 'a') => the arguments of GREATEST have no common type",
                "GREATEST(ARRAY[1], ARRAY[2]) => GREATEST cannot order ARRAY<INT32 NOT NULL> values",
                "GREATEST() => GREATEST needs at least one argument",
                "TYPEOF(1, 2) => TYPEOF needs 1 argument",
                "MOD(1) => MOD needs 2 arguments",
                "FORMAT() => FORMAT needs a pattern",
                "FORMAT(1, 2) => the FORMAT pattern must be STRING, got INT32",
                "EXTRACT(EPOCH FROM DATE '2026-11-07') => EXTRACT does not support EPOCH",
                "POSITION('a' IN 'b' FROM 2) => POSITION ... FROM is not supported",
                "SUBSTRING('abc', 'x') => SUBSTRING does not accept (STRING(3), STRING(1))",
            })
    void rejectsInvalidCallsWhilePlanning(String expression, String reason) {
        assertThat(run(expression)).startsWith("INVALID: ").contains(reason);
    }

    @Test
    void nonDeterministicFunctionsAreMarked() {
        assertThat(ENGINE.analyze("NOW()", EMPTY).deterministic()).isFalse();
        assertThat(ENGINE.analyze("CURRENT_DATE", EMPTY).deterministic()).isFalse();
        assertThat(ENGINE.analyze("RAND() + 1", EMPTY).deterministic()).isFalse();
        assertThat(ENGINE.analyze("RUN_START_TIME()", EMPTY).deterministic()).isTrue();
        assertThat(ENGINE.analyze("UPPER('a')", EMPTY).deterministic()).isTrue();
        assertThat(ENGINE.analyze("UNIX_TIMESTAMP(TIMESTAMP '2026-11-07 00:00:00')", EMPTY)
                        .deterministic())
                .isFalse();
    }

    @Test
    void functionsWorkOnColumns() {
        Expression expression = ENGINE.analyze(
                "UPPER(s) || '-' || CAST(YEAR(dt) AS STRING) || '-' || LPAD(CAST(i AS STRING), 4, '0')",
                Fixtures.SCHEMA);
        assertThat(ENGINE.compile(List.of(expression), OnError.FAIL, Fixtures.SHANGHAI)
                        .evaluate(Fixtures.row())[0])
                .isEqualTo("HELLO-2026-0032");
        assertThat(expression.type().root()).isEqualTo(TypeRoot.STRING);
        assertThat(ENGINE.compile(List.of(expression), OnError.FAIL, Fixtures.SHANGHAI)
                        .evaluate(GenericRow.of(
                                Fixtures.TABLE,
                                new Object[Fixtures.SCHEMA.columns().size()]))[0])
                .isNull();
    }
}
