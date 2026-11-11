/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class OptionRuleTest {

    enum Strategy {
        NONE,
        RANGE
    }

    static final Option<String> URL =
            Options.key("url").stringType().noDefault().withDescription("JDBC URL");
    static final Option<String> PASSWORD =
            Options.key("password").stringType().noDefault().withSensitive(true);
    static final Option<Integer> FETCH_SIZE = Options.key("fetch-size")
            .intType()
            .defaultValue(1000)
            .withRange(1, 100_000)
            .withGroup("throughput");
    static final Option<String> QUERY = Options.key("query").stringType().noDefault();
    static final Option<String> TABLE = Options.key("table").stringType().noDefault();
    static final Option<Strategy> SPLIT_STRATEGY =
            Options.key("split-strategy").enumType(Strategy.class).defaultValue(Strategy.NONE);
    static final Option<String> SPLIT_COLUMN =
            Options.key("split-column").stringType().noDefault();
    static final Option<Integer> SPLIT_COUNT =
            Options.key("split-count").intType().noDefault().withDeprecatedKeys("partition-num");

    static final OptionRule RULE = OptionRule.builder()
            .required(URL)
            .optional(PASSWORD, FETCH_SIZE, SPLIT_COUNT)
            .exclusive(QUERY, TABLE)
            .conditional(SPLIT_STRATEGY, Strategy.RANGE, SPLIT_COLUMN)
            .nativeNamespaces("jdbc.properties")
            .build();

    private static List<OptionViolation> validate(Map<String, ?> options) {
        return RULE.validate(ReadonlyConfig.of(options), "source");
    }

    private static Map<String, Object> valid() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("url", "jdbc:mysql://db/shop");
        options.put("table", "orders");
        return options;
    }

    @Test
    void acceptsValidOptions() {
        Map<String, Object> options = valid();
        options.put("fetch-size", "5000");
        options.put("jdbc.properties.useSSL", false);
        options.put("jdbc.properties", Map.of("connectTimeout", 3000));
        assertThat(validate(options)).isEmpty();
    }

    @Test
    void reportsMissingRequiredOptions() {
        Map<String, Object> options = valid();
        options.remove("url");
        assertThat(validate(options))
                .containsExactly(
                        new OptionViolation(ErrorCode.OPTION_REQUIRED, Map.of("option", "url", "node", "source")));
    }

    @Test
    void reportsWrongTypesAndRanges() {
        Map<String, Object> options = valid();
        options.put("fetch-size", 0);
        options.put("split-count", "many");
        List<OptionViolation> violations = validate(options);
        assertThat(violations)
                .extracting(v -> v.params().get("reason"))
                .containsExactly("must be at least 1", "expected an integer");
        assertThat(violations).allMatch(v -> v.code().equals(ErrorCode.OPTION_INVALID));
        assertThat(violations.get(1).params())
                .containsEntry("option", "split-count")
                .containsEntry("value", "many");
    }

    @Test
    void enforcesExactlyOneOptionOfAnExclusiveGroup() {
        Map<String, Object> none = valid();
        none.remove("table");
        assertThat(validate(none)).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo(ErrorCode.OPTION_REQUIRED);
            assertThat(v.params()).containsEntry("option", "query | table");
        });
        Map<String, Object> both = valid();
        both.put("query", "select 1");
        assertThat(validate(both)).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo(ErrorCode.OPTION_INVALID);
            assertThat(v.params())
                    .containsEntry("value", "query, table")
                    .containsEntry("reason", "set only one of query | table");
        });
    }

    @Test
    void requiresConditionalOptionsOnlyWhenTriggered() {
        Map<String, Object> options = valid();
        options.put("split-strategy", "range");
        assertThat(validate(options)).singleElement().satisfies(v -> assertThat(v.params())
                .containsEntry("option", "split-column"));
        options.put("split-column", "id");
        assertThat(validate(options)).isEmpty();
        options.put("split-strategy", "none");
        options.remove("split-column");
        assertThat(validate(options)).isEmpty();
        options.put("split-strategy", "hash");
        assertThat(validate(options))
                .singleElement()
                .satisfies(v -> assertThat(v.params().get("reason")).isEqualTo("expected one of [NONE, RANGE]"));
    }

    @Test
    void reportsUnknownOptionsWithSuggestions() {
        Map<String, Object> options = valid();
        options.put("fetchsize", 10);
        options.put("completely-different", 1);
        assertThat(validate(options))
                .extracting(v -> v.params().get("reason"))
                .containsExactly("unknown option; did you mean 'fetch-size'?", "unknown option");
    }

    @Test
    void acceptsDeprecatedKeys() {
        Map<String, Object> options = valid();
        options.put("partition-num", 4);
        assertThat(validate(options)).isEmpty();
        assertThat(ReadonlyConfig.of(options).get(SPLIT_COUNT)).isEqualTo(4);
    }

    @Test
    void masksSensitiveValues() {
        Map<String, Object> options = valid();
        options.put("password", Map.of("nested", "secret"));
        assertThat(validate(options)).singleElement().satisfies(v -> assertThat(v.params())
                .containsEntry("value", OptionRule.MASK));
    }

    @Test
    void throwsTheFirstViolationWithScope() {
        assertThatThrownBy(() -> RULE.validateOrThrow(ReadonlyConfig.of(Map.of("table", "t")), "source"))
                .isInstanceOf(PipeForgeException.class)
                .hasMessage("PF-CONF-0001 Required option 'url' is missing in node 'source'")
                .satisfies(
                        e -> assertThat(((PipeForgeException) e).scope().node()).isEqualTo("source"));
        RULE.validateOrThrow(ReadonlyConfig.of(valid()), "source");
    }

    @Test
    void listsEveryDeclaredOptionOnce() {
        assertThat(RULE.options())
                .extracting(Option::key)
                .containsExactly(
                        "url",
                        "query",
                        "table",
                        "password",
                        "fetch-size",
                        "split-count",
                        "split-strategy",
                        "split-column");
        assertThat(RULE.required()).containsExactly(URL);
        assertThat(RULE.exclusive()).containsExactly(List.of(QUERY, TABLE));
        assertThat(RULE.conditions()).singleElement().satisfies(c -> assertThat(c.value())
                .isEqualTo(Strategy.RANGE));
        assertThat(RULE.nativeNamespaces()).containsExactly("jdbc.properties");
    }

    @Test
    void rejectsBrokenRules() {
        assertThatThrownBy(
                        () -> OptionRule.builder().required(URL).optional(URL).build())
                .hasMessageContaining("declared twice");
        assertThatThrownBy(() -> OptionRule.builder().exclusive(URL)).hasMessageContaining("at least two");
        assertThatThrownBy(() -> Options.key("Bad_Key")).hasMessageContaining("lower case");
        assertThatThrownBy(() -> URL.withRange(1, 2)).hasMessageContaining("Only numeric");
    }

    @Test
    void computesEditDistance() {
        assertThat(OptionRule.distance("fetchsize", "fetch-size")).isEqualTo(1);
        assertThat(OptionRule.distance("", "abc")).isEqualTo(3);
        assertThat(OptionRule.distance("same", "same")).isZero();
    }
}
