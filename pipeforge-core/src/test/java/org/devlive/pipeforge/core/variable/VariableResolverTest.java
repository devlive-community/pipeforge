/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.core.Errors;
import org.devlive.pipeforge.core.TestComponents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VariableResolverTest {

    private static VariableResolver resolver(Map<String, String> definitions) {
        return new VariableResolver(definitions, TestComponents.context().build(), TestComponents.SHANGHAI, "orders");
    }

    private static String resolve(String text) {
        return resolver(Map.of()).resolve(text);
    }

    @ParameterizedTest(name = "{0} => {1}")
    @CsvSource(
            delimiterString = " => ",
            quoteCharacter = '"',
            textBlock =
                    """
            ${date:yyyy-MM-dd}                                => 2026-11-07
            ${date:yyyy-MM-dd HH:mm:ss}                       => 2026-11-07 08:30:00
            ${date:yyyyMMdd,-1d}                              => 20261106
            ${date:yyyy-MM-dd,+1d}                            => 2026-11-08
            ${date:yyyyMM,-1M}                                => 202610
            ${date:yyyy-MM-dd HH:00:00,-1h}                   => 2026-11-07 07:00:00
            ${date:HH:mm:ss,-90s}                             => 08:28:30
            ${date:HH:mm,+15min}                              => 08:45
            ${date:yyyy-MM-dd,-1w}                            => 2026-10-31
            ${date:yyyy,-1y}                                  => 2025
            ${date:yyyy-MM-dd,-1M+3d}                         => 2026-10-10
            ${date:yyyy-MM-dd,-1M,end-of-month}               => 2026-10-31
            ${date:yyyy-MM-dd,,start-of-month}                => 2026-11-01
            ${date:yyyy-MM-dd HH:mm:ss,,end-of-day}           => 2026-11-07 23:59:59
            ${date:yyyy-MM-dd HH:mm,-1d,start-of-day}         => 2026-11-06 00:00
            ${date:yyyy-MM-dd EEE,,start-of-week}             => 2026-11-02 Mon
            ${date:yyyy-MM-dd,-8M,end-of-month}               => 2026-03-31
            "dt=${date:yyyyMMdd,-1d}/part"                    => dt=20261106/part
            """)
    void formatsTheScheduledTimeInTheJobTimeZone(String text, String expected) {
        assertThat(resolve(text)).isEqualTo(expected);
    }

    @Test
    void endOfMonthHandlesFebruaryAndLeapYears() {
        VariableContext leap = TestComponents.context()
                .scheduledTime(Instant.parse("2028-03-31T04:00:00Z"))
                .build();
        VariableResolver resolver = new VariableResolver(Map.of(), leap, ZoneId.of("UTC"), "job");
        assertThat(resolver.resolve("${date:yyyy-MM-dd,-1M}")).isEqualTo("2028-02-29");
        assertThat(resolver.resolve("${date:yyyy-MM-dd,-13M,end-of-month}")).isEqualTo("2027-02-28");
    }

    @Test
    void theTimeZoneDecidesTheDay() {
        VariableContext context = TestComponents.context().build();
        assertThat(new VariableResolver(Map.of(), context, ZoneId.of("UTC"), "job").resolve("${date:yyyy-MM-dd HH}"))
                .isEqualTo("2026-11-07 00");
        assertThat(new VariableResolver(Map.of(), context, ZoneId.of("America/Los_Angeles"), "job")
                        .resolve("${date:yyyy-MM-dd HH}"))
                .isEqualTo("2026-11-06 16");
    }

    @Test
    void jobVariablesUseOverridesThenDefinitionsThenDefaults() {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put("biz_date", "${date:yyyy-MM-dd,-1d}");
        definitions.put("path", "/data/${var:biz_date}");
        definitions.put("region", "cn");
        VariableContext context =
                TestComponents.context().override("region", "us").build();
        VariableResolver resolver = new VariableResolver(definitions, context, TestComponents.SHANGHAI, "orders");

        assertThat(resolver.resolve("${var:path}")).isEqualTo("/data/2026-11-06");
        assertThat(resolver.resolve("${var:region}")).isEqualTo("us");
        assertThat(resolver.resolve("${var:missing:-x}")).isEqualTo("x");
        assertThat(resolver.resolve("${var:missing:-}")).isEmpty();
        assertThat(resolver.variables())
                .containsExactly(
                        Map.entry("biz_date", "2026-11-06"),
                        Map.entry("path", "/data/2026-11-06"),
                        Map.entry("region", "us"));
    }

    @Test
    void overridesMayIntroduceNewVariables() {
        VariableContext context =
                TestComponents.context().override("extra", "${date:yyyy}").build();
        VariableResolver resolver = new VariableResolver(Map.of("a", "1"), context, TestComponents.SHANGHAI, "job");
        assertThat(resolver.variables()).containsExactly(Map.entry("a", "1"), Map.entry("extra", "2026"));
    }

    @Test
    void environmentRunAndJobVariables() {
        assertThat(resolve("${env:REGION}/${env:MISSING:-none}")).isEqualTo("cn-east/none");
        assertThat(resolve("${run:id}#${run:attempt}")).isEqualTo("run-42#2");
        assertThat(resolve("${run:start-time}")).isEqualTo("2026-11-07T08:31:15.123+08:00");
        assertThat(resolve("${job:name}")).isEqualTo("orders");
        assertThat(resolve("${run:other:-x}${job:other:-y}")).isEqualTo("xy");
    }

    @Test
    void textsWithOtherPrefixesAndEscapesAreKept() {
        assertThat(resolve("ods_${1}")).isEqualTo("ods_${1}");
        assertThat(resolve("${table}_${unknown:x}")).isEqualTo("${table}_${unknown:x}");
        assertThat(resolve("$${var:x} costs $5")).isEqualTo("${var:x} costs $5");
        assertThat(resolve("${ no prefix")).isEqualTo("${ no prefix");
        assertThat(resolve("plain")).isEqualTo("plain");
        assertThat(resolve("${date:VV}")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void undefinedVariablesNameTheVariable() {
        assertThat(Errors.message(ErrorCode.VARIABLE_UNDEFINED, () -> resolve("${var:biz_date}")))
                .isEqualTo("Variable '${var:biz_date}' is not defined");
        assertThat(Errors.thrown(ErrorCode.VARIABLE_UNDEFINED, () -> resolve("${env:NOPE}"))
                        .hint("en-US"))
                .contains("${env:NOPE:-default}");
    }

    @Test
    void malformedVariablesExplainTheProblem() {
        assertThatThrownBy(() -> resolve("${var:x")).hasMessageContaining("not closed");
        assertThatThrownBy(() -> resolve("${var:1x}")).hasMessageContaining("invalid variable name '1x'");
        assertThatThrownBy(() -> resolve("${env:A B}")).hasMessageContaining("invalid environment variable name");
        assertThatThrownBy(() -> resolve("${date:yyyy,-1m}")).hasMessageContaining("invalid date offset '-1m'");
        assertThatThrownBy(() -> resolve("${date:yyyy,1d}")).hasMessageContaining("invalid date offset");
        assertThatThrownBy(() -> resolve("${date:yyyy,,end-of-week}")).hasMessageContaining("unknown boundary");
        assertThatThrownBy(() -> resolve("${date:yyyy,-1d,start-of-day,x}")).hasMessageContaining("too many parts");
        assertThatThrownBy(() -> resolve("${date:{bad}")).hasMessageContaining("invalid date format");
    }

    @Test
    void cyclesAndServerVariablesAreRejected() {
        VariableResolver cyclic = resolver(Map.of("a", "${var:b}", "b", "x${var:a}"));
        assertThatThrownBy(() -> cyclic.resolve("${var:a}")).hasMessage("variables refer to each other: a -> b -> a");
        assertThatThrownBy(() -> resolve("${secret:mysql-prod-pwd}"))
                .hasMessageContaining("resolved by the PipeForge server");
        assertThatThrownBy(() -> resolve("${file:hive.keytab}")).hasMessageContaining("${file:hive.keytab}");
        assertThatThrownBy(() -> resolve("${run:nope}")).hasMessageContaining("unknown run variable");
        assertThatThrownBy(() -> resolve("${job:id}")).hasMessageContaining("unknown job variable");
    }

    @Test
    void theNameAndTimeZoneCannotUseWhatDependsOnThem() {
        VariableContext context = TestComponents.context().build();
        assertThatThrownBy(() -> new VariableResolver(Map.of(), context, null, null).resolve("${date:yyyy}"))
                .hasMessageContaining("cannot be used in the time zone");
        assertThatThrownBy(() -> new VariableResolver(Map.of(), context, ZoneId.of("UTC"), null).resolve("${job:name}"))
                .hasMessageContaining("cannot be used in the job name");
    }
}
