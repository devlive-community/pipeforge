/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.option;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.annotation.Stable;
import org.jspecify.annotations.Nullable;

/**
 * Starts option declarations:
 *
 * <pre>{@code
 * Option<Integer> FETCH_SIZE = Options.key("fetch-size").intType().defaultValue(1000)
 *         .withDescription("Rows fetched per round trip").withRange(1, null);
 * }</pre>
 */
@Stable
public final class Options {

    private Options() {}

    public static KeyBuilder key(String key) {
        if (!key.matches("[a-z0-9]+(?:[.-][a-z0-9]+)*")) {
            throw new IllegalArgumentException("Option keys are lower case words joined by '-' or '.': " + key);
        }
        return new KeyBuilder(key);
    }

    /** Chooses the type of an option. */
    public static final class KeyBuilder {
        private final String key;

        private KeyBuilder(String key) {
            this.key = key;
        }

        public TypedBuilder<String> stringType() {
            return new TypedBuilder<>(key, OptionType.STRING, String.class, List.of());
        }

        public TypedBuilder<Integer> intType() {
            return new TypedBuilder<>(key, OptionType.INT, Integer.class, List.of());
        }

        public TypedBuilder<Long> longType() {
            return new TypedBuilder<>(key, OptionType.LONG, Long.class, List.of());
        }

        public TypedBuilder<Double> doubleType() {
            return new TypedBuilder<>(key, OptionType.DOUBLE, Double.class, List.of());
        }

        public TypedBuilder<Boolean> booleanType() {
            return new TypedBuilder<>(key, OptionType.BOOLEAN, Boolean.class, List.of());
        }

        public TypedBuilder<Duration> durationType() {
            return new TypedBuilder<>(key, OptionType.DURATION, Duration.class, List.of());
        }

        public <E extends Enum<E>> TypedBuilder<E> enumType(Class<E> enumClass) {
            List<String> values =
                    Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).toList();
            return new TypedBuilder<>(key, OptionType.ENUM, enumClass, values);
        }

        public TypedBuilder<List<String>> listType() {
            return new TypedBuilder<>(key, OptionType.LIST, List.class, List.of());
        }

        public TypedBuilder<Map<String, String>> mapType() {
            return new TypedBuilder<>(key, OptionType.MAP, Map.class, List.of());
        }
    }

    /** Sets the default value of an option. */
    public static final class TypedBuilder<T> {
        private final String key;
        private final OptionType type;
        private final Class<?> valueClass;
        private final List<String> enumValues;

        private TypedBuilder(String key, OptionType type, Class<?> valueClass, List<String> enumValues) {
            this.key = key;
            this.type = type;
            this.valueClass = valueClass;
            this.enumValues = enumValues;
        }

        public Option<T> defaultValue(T value) {
            return build(value);
        }

        public Option<T> noDefault() {
            return build(null);
        }

        private Option<T> build(@Nullable T value) {
            return new Option<>(key, type, valueClass, value, "", false, null, null, null, enumValues, List.of(), null);
        }
    }
}
