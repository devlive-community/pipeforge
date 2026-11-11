/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReadonlyConfigTest {

    enum Mode {
        AT_LEAST_ONCE,
        EXACTLY_ONCE
    }

    static final Option<Duration> TIMEOUT =
            Options.key("timeout").durationType().defaultValue(Duration.ofSeconds(30));
    static final Option<Boolean> ENABLED = Options.key("enabled").booleanType().defaultValue(false);
    static final Option<Long> LIMIT = Options.key("limit").longType().noDefault();
    static final Option<Double> RATIO =
            Options.key("ratio").doubleType().noDefault().withRange(0, 1.5);
    static final Option<Mode> MODE = Options.key("mode").enumType(Mode.class).noDefault();
    static final Option<List<String>> TABLES = Options.key("tables").listType().noDefault();
    static final Option<Map<String, String>> HEADERS =
            Options.key("headers").mapType().noDefault();
    static final Option<String> NAME = Options.key("name").stringType().defaultValue("job");

    @Test
    void returnsDefaultsForMissingOptions() {
        ReadonlyConfig config = ReadonlyConfig.empty();
        assertThat(config.get(TIMEOUT)).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.get(LIMIT)).isNull();
        assertThat(config.getOptional(NAME)).contains("job");
        assertThat(config.getOptional(LIMIT)).isEmpty();
        assertThat(config.contains(NAME)).isFalse();
    }

    @Test
    void convertsWrittenValues() {
        ReadonlyConfig config = ReadonlyConfig.of(Map.of(
                "timeout", "5m",
                "enabled", "yes",
                "limit", "1_000_000",
                "ratio", 1,
                "mode", "exactly-once",
                "tables", "a, b ,,c",
                "name", 42));
        assertThat(config.get(TIMEOUT)).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.get(ENABLED)).isTrue();
        assertThat(config.get(LIMIT)).isEqualTo(1_000_000L);
        assertThat(config.get(RATIO)).isEqualTo(1.0);
        assertThat(config.get(MODE)).isEqualTo(Mode.EXACTLY_ONCE);
        assertThat(config.get(TABLES)).containsExactly("a", "b", "c");
        assertThat(config.get(NAME)).isEqualTo("42");
        assertThat(config.keys()).hasSize(7);
        assertThat(config.toMap()).containsEntry("limit", "1_000_000");
    }

    @Test
    void parsesDurations() {
        assertThat(duration("200ms")).isEqualTo(Duration.ofMillis(200));
        assertThat(duration("45s")).isEqualTo(Duration.ofSeconds(45));
        assertThat(duration("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(duration("1d")).isEqualTo(Duration.ofDays(1));
        assertThat(duration("PT1M30S")).isEqualTo(Duration.ofSeconds(90));
        assertThat(duration(1500)).isEqualTo(Duration.ofMillis(1500));
        assertThatThrownBy(() -> duration("soon")).hasMessage("expected a duration such as 30s, 5m, 1h or 200ms");
    }

    private static Duration duration(Object raw) {
        return ReadonlyConfig.convert(TIMEOUT, raw);
    }

    @Test
    void reportsConversionProblems() {
        assertThatThrownBy(() -> ReadonlyConfig.convert(ENABLED, "maybe")).hasMessage("expected true or false");
        assertThatThrownBy(() -> ReadonlyConfig.convert(RATIO, "x")).hasMessage("expected a number");
        assertThatThrownBy(() -> ReadonlyConfig.convert(RATIO, 2)).hasMessage("must be at most 1.5");
        assertThatThrownBy(() -> ReadonlyConfig.convert(LIMIT, null)).hasMessage("a value is required");
        assertThatThrownBy(() -> ReadonlyConfig.convert(NAME, List.of())).hasMessage("expected a string");
        assertThatThrownBy(() -> ReadonlyConfig.convert(TABLES, Map.of())).hasMessage("expected a list");
        assertThatThrownBy(() -> ReadonlyConfig.convert(TABLES, List.of(List.of())))
                .hasMessage("expected a list of plain values");
        assertThatThrownBy(() -> ReadonlyConfig.convert(HEADERS, "a=b")).hasMessage("expected a mapping");
        Option<Integer> small = Options.key("small").intType().noDefault();
        assertThatThrownBy(() -> ReadonlyConfig.convert(small, 3_000_000_000L)).hasMessage("integer out of range");
        assertThat(ReadonlyConfig.convert(TABLES, List.of("x", 1))).containsExactly("x", "1");
        assertThat(ReadonlyConfig.convert(ENABLED, false)).isFalse();
        assertThat(ReadonlyConfig.convert(ENABLED, "OFF")).isFalse();
    }

    @Test
    void readsMapOptionsAndNativeNamespaces() {
        ReadonlyConfig config = ReadonlyConfig.of(Map.of(
                "headers",
                Map.of("a", "1", "nested", Map.of("b", 2)),
                "kafka",
                Map.of("acks", "all"),
                "kafka.linger.ms",
                5,
                "kafka-other",
                "x"));
        assertThat(config.get(HEADERS)).containsEntry("a", "1").containsEntry("nested.b", "2");
        assertThat(config.nativeConfig("kafka")).containsOnly(Map.entry("acks", "all"), Map.entry("linger.ms", "5"));
        assertThat(config.nativeConfig("absent")).isEmpty();
        ReadonlyConfig dotted = ReadonlyConfig.of(Map.of("headers.x", "y"));
        assertThat(dotted.contains(HEADERS)).isTrue();
        assertThat(dotted.get(HEADERS)).containsOnly(Map.entry("x", "y"));
        assertThat(dotted.raw("headers.x")).isEqualTo("y");
        assertThat(dotted).hasToString("{headers.x=y}");
    }

    @Test
    void optionsAreImmutableDescriptions() {
        Option<Integer> option = Options.key("batch-size")
                .intType()
                .defaultValue(100)
                .withDescription("Rows per batch")
                .withGroup("throughput")
                .withRange(1, null)
                .withSince("0.1.0")
                .withDeprecatedKeys("batch");
        assertThat(option.key()).isEqualTo("batch-size");
        assertThat(option.type()).isEqualTo(OptionType.INT);
        assertThat(option.valueClass()).isEqualTo(Integer.class);
        assertThat(option.defaultValue()).isEqualTo(100);
        assertThat(option.hasDefault()).isTrue();
        assertThat(option.description()).isEqualTo("Rows per batch");
        assertThat(option.group()).isEqualTo("throughput");
        assertThat(option.min()).isEqualTo(1.0);
        assertThat(option.max()).isNull();
        assertThat(option.since()).isEqualTo("0.1.0");
        assertThat(option.deprecatedKeys()).containsExactly("batch");
        assertThat(option.isSensitive()).isFalse();
        assertThat(option)
                .hasToString("batch-size (int)")
                .isEqualTo(Options.key("batch-size").intType().noDefault());
        assertThat(option.hashCode())
                .isEqualTo(Options.key("batch-size").intType().noDefault().hashCode());
        assertThat(MODE.enumValues()).containsExactly("AT_LEAST_ONCE", "EXACTLY_ONCE");
    }
}
