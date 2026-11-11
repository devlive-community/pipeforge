/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.option;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.devlive.pipeforge.api.annotation.Stable;
import org.jspecify.annotations.Nullable;

/**
 * The options of a node as written in the job, read through declared {@link Option}s. Values
 * are converted to the option type on access; a missing value yields the default.
 *
 * <p>Native configuration is passed through by namespace: {@code jdbc.properties.useSSL: false}
 * and a nested {@code jdbc.properties: {useSSL: false}} both appear in {@code
 * nativeConfig("jdbc.properties")}.
 */
@Stable
public final class ReadonlyConfig {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)", Pattern.CASE_INSENSITIVE);

    private final Map<String, Object> values;

    private ReadonlyConfig(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public static ReadonlyConfig of(Map<String, ?> values) {
        return new ReadonlyConfig(new LinkedHashMap<>(values));
    }

    public static ReadonlyConfig empty() {
        return of(Map.of());
    }

    /** The keys as written, in order. */
    public Set<String> keys() {
        return values.keySet();
    }

    public Map<String, Object> toMap() {
        return values;
    }

    /** The raw value written for {@code key}, or {@code null}. */
    public @Nullable Object raw(String key) {
        return values.get(key);
    }

    /** The key under which an option is written: its key, or a deprecated one. */
    public Optional<String> keyOf(Option<?> option) {
        if (values.containsKey(option.key())) {
            return Optional.of(option.key());
        }
        if (option.type() == OptionType.MAP && !namespace(option.key()).isEmpty()) {
            return Optional.of(option.key());
        }
        return option.deprecatedKeys().stream().filter(values::containsKey).findFirst();
    }

    public boolean contains(Option<?> option) {
        return keyOf(option).isPresent();
    }

    /**
     * The converted value, or the default when the option is not set.
     *
     * @throws IllegalArgumentException if the written value cannot be converted
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(Option<T> option) {
        Optional<String> key = keyOf(option);
        if (key.isEmpty()) {
            return option.defaultValue();
        }
        if (option.type() == OptionType.MAP) {
            return (T) namespace(option.key());
        }
        return convert(option, values.get(key.get()));
    }

    /** The converted value if the option is set or has a default. */
    public <T> Optional<T> getOptional(Option<T> option) {
        return Optional.ofNullable(get(option));
    }

    /** Native configuration under a namespace, with the namespace prefix removed. */
    public Map<String, String> nativeConfig(String namespace) {
        return namespace(namespace);
    }

    private Map<String, String> namespace(String namespace) {
        Map<String, String> result = new LinkedHashMap<>();
        Object nested = values.get(namespace);
        if (nested instanceof Map<?, ?> map) {
            flatten("", map, result);
        }
        String prefix = namespace + ".";
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() != null) {
                result.put(entry.getKey().substring(prefix.length()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, String> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(key + ".", child, out);
            } else if (entry.getValue() != null) {
                out.put(key, String.valueOf(entry.getValue()));
            }
        }
    }

    /**
     * Converts a written value to the type of an option.
     *
     * @throws IllegalArgumentException with a human-readable reason if it cannot be converted
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> T convert(Option<T> option, @Nullable Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("a value is required");
        }
        String text = String.valueOf(raw).trim();
        Object value =
                switch (option.type()) {
                    case STRING -> raw instanceof Map || raw instanceof List
                            ? fail("expected a string")
                            : String.valueOf(raw);
                    case INT -> toLong(raw, text, Integer.MIN_VALUE, Integer.MAX_VALUE)
                            .intValue();
                    case LONG -> toLong(raw, text, Long.MIN_VALUE, Long.MAX_VALUE);
                    case DOUBLE -> toDouble(raw, text);
                    case BOOLEAN -> toBoolean(raw, text);
                    case DURATION -> toDuration(raw, text);
                    case ENUM -> {
                        String upper = text.toUpperCase(Locale.ROOT).replace('-', '_');
                        if (!option.enumValues().contains(upper)) {
                            fail("expected one of " + option.enumValues());
                        }
                        yield Enum.valueOf((Class) option.valueClass(), upper);
                    }
                    case LIST -> toList(raw, text);
                    case OBJECT_LIST -> toObjectList(raw);
                    case MAP -> {
                        if (!(raw instanceof Map<?, ?> map)) {
                            yield fail("expected a mapping");
                        }
                        Map<String, String> result = new LinkedHashMap<>();
                        flatten("", map, result);
                        yield result;
                    }
                };
        checkRange(option, value);
        return (T) value;
    }

    private static Long toLong(Object raw, String text, long min, long max) {
        long value;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            value = ((Number) raw).longValue();
        } else {
            try {
                value = Long.parseLong(text.replace("_", ""));
            } catch (NumberFormatException e) {
                return fail("expected an integer");
            }
        }
        if (value < min || value > max) {
            return fail("integer out of range");
        }
        return value;
    }

    private static Double toDouble(Object raw, String text) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fail("expected a number");
        }
    }

    private static Boolean toBoolean(Object raw, String text) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on" -> Boolean.TRUE;
            case "false", "no", "off" -> Boolean.FALSE;
            default -> fail("expected true or false");
        };
    }

    private static Duration toDuration(Object raw, String text) {
        if (raw instanceof Integer || raw instanceof Long) {
            return Duration.ofMillis(((Number) raw).longValue());
        }
        Matcher matcher = DURATION.matcher(text);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        }
        try {
            return Duration.parse(text.toUpperCase(Locale.ROOT));
        } catch (DateTimeParseException e) {
            return fail("expected a duration such as 30s, 5m, 1h or 200ms");
        }
    }

    private static List<Map<String, String>> toObjectList(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return fail("expected a list of objects");
        }
        List<Map<String, String>> list = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                return fail("expected a list of objects");
            }
            Map<String, String> entry = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : map.entrySet()) {
                Object value = field.getValue();
                if (value instanceof Map || value instanceof List) {
                    return fail("'" + field.getKey() + "' must be a plain value, not a list or an object");
                }
                if (value != null) {
                    entry.put(String.valueOf(field.getKey()), String.valueOf(value));
                }
            }
            list.add(Collections.unmodifiableMap(entry));
        }
        return List.copyOf(list);
    }

    private static List<String> toList(Object raw, String text) {
        List<String> list = new ArrayList<>();
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map || item instanceof List) {
                    return fail("expected a list of plain values");
                }
                list.add(String.valueOf(item));
            }
            return List.copyOf(list);
        }
        if (raw instanceof Map) {
            return fail("expected a list");
        }
        for (String part : text.split(",", -1)) {
            if (!part.isBlank()) {
                list.add(part.trim());
            }
        }
        return List.copyOf(list);
    }

    private static void checkRange(Option<?> option, Object value) {
        if (!(value instanceof Number number)) {
            return;
        }
        double v = number.doubleValue();
        if (option.min() != null && v < option.min()) {
            fail("must be at least " + format(option.min()));
        }
        if (option.max() != null && v > option.max()) {
            fail("must be at most " + format(option.max()));
        }
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    @SuppressWarnings("TypeParameterUnusedInFormals") // lets fail() stand in for any value in switch arms
    private static <T> T fail(String reason) {
        throw new IllegalArgumentException(reason);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
