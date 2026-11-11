/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.option;

import java.util.List;
import java.util.Objects;
import org.devlive.pipeforge.api.annotation.Stable;
import org.jspecify.annotations.Nullable;

/**
 * A declared option of a plugin: key, type, default value, documentation and constraints.
 * Instances are immutable; the {@code with*} methods return copies. Create options with {@link
 * Options#key(String)}.
 *
 * @param <T> the Java type of the value
 */
@Stable
public final class Option<T> {

    private final String key;
    private final OptionType type;
    private final Class<?> valueClass;
    private final @Nullable T defaultValue;
    private final String description;
    private final boolean sensitive;
    private final @Nullable String group;
    private final @Nullable Double min;
    private final @Nullable Double max;
    private final List<String> enumValues;
    private final List<String> deprecatedKeys;
    private final @Nullable String since;

    @SuppressWarnings("TooManyParameters")
    Option(
            String key,
            OptionType type,
            Class<?> valueClass,
            @Nullable T defaultValue,
            String description,
            boolean sensitive,
            @Nullable String group,
            @Nullable Double min,
            @Nullable Double max,
            List<String> enumValues,
            List<String> deprecatedKeys,
            @Nullable String since) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = type;
        this.valueClass = valueClass;
        this.defaultValue = defaultValue;
        this.description = description;
        this.sensitive = sensitive;
        this.group = group;
        this.min = min;
        this.max = max;
        this.enumValues = List.copyOf(enumValues);
        this.deprecatedKeys = List.copyOf(deprecatedKeys);
        this.since = since;
    }

    public String key() {
        return key;
    }

    public OptionType type() {
        return type;
    }

    /** The Java class of values (the enum class for {@link OptionType#ENUM}). */
    public Class<?> valueClass() {
        return valueClass;
    }

    public @Nullable T defaultValue() {
        return defaultValue;
    }

    public boolean hasDefault() {
        return defaultValue != null;
    }

    public String description() {
        return description;
    }

    /** Sensitive values (passwords, tokens) are masked in logs, the UI and exported plans. */
    public boolean isSensitive() {
        return sensitive;
    }

    /** Group for forms and documentation, e.g. {@code connection} or {@code throughput}. */
    public @Nullable String group() {
        return group;
    }

    /** Inclusive lower bound of numeric values. */
    public @Nullable Double min() {
        return min;
    }

    /** Inclusive upper bound of numeric values. */
    public @Nullable Double max() {
        return max;
    }

    /** Allowed values of an enum option. */
    public List<String> enumValues() {
        return enumValues;
    }

    /** Former names of the option that are still accepted. */
    public List<String> deprecatedKeys() {
        return deprecatedKeys;
    }

    /** The plugin version that introduced the option. */
    public @Nullable String since() {
        return since;
    }

    private Option<T> copy(
            String newDescription,
            boolean newSensitive,
            @Nullable String newGroup,
            @Nullable Double newMin,
            @Nullable Double newMax,
            List<String> newDeprecated,
            @Nullable String newSince) {
        return new Option<>(
                key,
                type,
                valueClass,
                defaultValue,
                newDescription,
                newSensitive,
                newGroup,
                newMin,
                newMax,
                enumValues,
                newDeprecated,
                newSince);
    }

    public Option<T> withDescription(String value) {
        return copy(value, sensitive, group, min, max, deprecatedKeys, since);
    }

    public Option<T> withSensitive(boolean value) {
        return copy(description, value, group, min, max, deprecatedKeys, since);
    }

    public Option<T> withGroup(String value) {
        return copy(description, sensitive, value, min, max, deprecatedKeys, since);
    }

    /** Restricts numeric values to {@code [min, max]}; either bound may be {@code null}. */
    public Option<T> withRange(@Nullable Number minValue, @Nullable Number maxValue) {
        if (type != OptionType.INT && type != OptionType.LONG && type != OptionType.DOUBLE) {
            throw new IllegalStateException("Only numeric options have a range: " + key);
        }
        return copy(
                description,
                sensitive,
                group,
                minValue == null ? null : minValue.doubleValue(),
                maxValue == null ? null : maxValue.doubleValue(),
                deprecatedKeys,
                since);
    }

    public Option<T> withDeprecatedKeys(String... keys) {
        return copy(description, sensitive, group, min, max, List.of(keys), since);
    }

    public Option<T> withSince(String version) {
        return copy(description, sensitive, group, min, max, deprecatedKeys, version);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Option<?> option && option.key.equals(key) && option.type == type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, type);
    }

    @Override
    public String toString() {
        return key + " (" + type.name().toLowerCase(java.util.Locale.ROOT) + ")";
    }
}
