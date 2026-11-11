/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.common.error.ErrorCode;

/**
 * The options a plugin accepts and the rules between them. One rule serves three purposes:
 * validating jobs while planning, rendering forms in the UI and generating the plugin page.
 *
 * <pre>{@code
 * OptionRule.builder()
 *         .required(URL, USERNAME)
 *         .optional(FETCH_SIZE, SPLIT_COLUMN)
 *         .exclusive(QUERY, TABLE)                         // exactly one of them
 *         .conditional(SPLIT_STRATEGY, Strategy.RANGE, SPLIT_COLUMN)
 *         .nativeNamespaces("jdbc.properties")
 *         .build();
 * }</pre>
 */
@Stable
public final class OptionRule {

    /** Values of sensitive options are replaced with this text in errors. */
    public static final String MASK = "******";

    /**
     * When {@code trigger} has {@code value}, the {@code required} options must be set.
     *
     * @param trigger the option whose value is checked
     * @param value the triggering value (compared after conversion)
     * @param required the options that become required
     */
    public record Condition(Option<?> trigger, Object value, List<Option<?>> required) {
        public Condition {
            required = List.copyOf(required);
        }
    }

    private final List<Option<?>> required;
    private final List<Option<?>> optional;
    private final List<List<Option<?>>> exclusive;
    private final List<Condition> conditions;
    private final List<String> nativeNamespaces;

    private OptionRule(Builder builder) {
        this.required = List.copyOf(builder.required);
        this.optional = List.copyOf(builder.optional);
        this.exclusive = builder.exclusive.stream().map(List::copyOf).toList();
        this.conditions = List.copyOf(builder.conditions);
        this.nativeNamespaces = List.copyOf(builder.nativeNamespaces);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Option<?>> required() {
        return required;
    }

    public List<Option<?>> optional() {
        return optional;
    }

    /** Groups of options of which exactly one must be set. */
    public List<List<Option<?>>> exclusive() {
        return exclusive;
    }

    public List<Condition> conditions() {
        return conditions;
    }

    /** Prefixes under which native configuration is passed through unchecked. */
    public List<String> nativeNamespaces() {
        return nativeNamespaces;
    }

    /** Every declared option once, in declaration order. */
    public List<Option<?>> options() {
        Map<String, Option<?>> all = new LinkedHashMap<>();
        required.forEach(o -> all.putIfAbsent(o.key(), o));
        exclusive.forEach(group -> group.forEach(o -> all.putIfAbsent(o.key(), o)));
        optional.forEach(o -> all.putIfAbsent(o.key(), o));
        for (Condition condition : conditions) {
            all.putIfAbsent(condition.trigger().key(), condition.trigger());
            condition.required().forEach(o -> all.putIfAbsent(o.key(), o));
        }
        return List.copyOf(all.values());
    }

    /**
     * Checks a node's options: required and conditionally required options are present,
     * values have the right type and range, exactly one option of each exclusive group is set,
     * and no unknown option is written outside the native namespaces.
     *
     * @param config the options of the node
     * @param node the node name, used in the messages
     * @return the problems found, in a stable order; empty when the options are valid
     */
    public List<OptionViolation> validate(ReadonlyConfig config, String node) {
        List<OptionViolation> violations = new ArrayList<>();
        for (Option<?> option : required) {
            if (!config.contains(option) && !option.hasDefault()) {
                violations.add(missing(option.key(), node));
            }
        }
        for (Option<?> option : options()) {
            config.keyOf(option).ifPresent(key -> checkValue(config, option, key, node, violations));
        }
        for (List<Option<?>> group : exclusive) {
            List<String> present =
                    group.stream().filter(config::contains).map(Option::key).toList();
            String keys = group.stream().map(Option::key).collect(Collectors.joining(" | "));
            if (present.isEmpty()) {
                violations.add(missing(keys, node));
            } else if (present.size() > 1) {
                violations.add(invalid(keys, node, String.join(", ", present), "set only one of " + keys));
            }
        }
        for (Condition condition : conditions) {
            if (matches(config, condition)) {
                for (Option<?> option : condition.required()) {
                    if (!config.contains(option) && !option.hasDefault()) {
                        violations.add(missing(option.key(), node));
                    }
                }
            }
        }
        violations.addAll(unknownKeys(config, node));
        return violations;
    }

    /**
     * Validates and throws the first problem as a {@link
     * org.devlive.pipeforge.common.error.PipeForgeException}.
     */
    public void validateOrThrow(ReadonlyConfig config, String node) {
        List<OptionViolation> violations = validate(config, node);
        if (!violations.isEmpty()) {
            throw violations.get(0).toException();
        }
    }

    private static void checkValue(
            ReadonlyConfig config, Option<?> option, String key, String node, List<OptionViolation> out) {
        if (option.type() == OptionType.MAP && config.raw(key) == null) {
            return;
        }
        try {
            ReadonlyConfig.convert(option, config.raw(key));
        } catch (IllegalArgumentException e) {
            Object raw = config.raw(key);
            String shown = option.isSensitive() ? MASK : String.valueOf(raw);
            out.add(invalid(key, node, shown, String.valueOf(e.getMessage())));
        }
    }

    private static boolean matches(ReadonlyConfig config, Condition condition) {
        if (!config.contains(condition.trigger()) && !condition.trigger().hasDefault()) {
            return false;
        }
        try {
            Object actual = config.get(condition.trigger());
            return Objects.equals(actual, condition.value());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<OptionViolation> unknownKeys(ReadonlyConfig config, String node) {
        Set<String> known = new LinkedHashSet<>();
        for (Option<?> option : options()) {
            known.add(option.key());
            known.addAll(option.deprecatedKeys());
        }
        List<OptionViolation> violations = new ArrayList<>();
        for (String key : config.keys()) {
            if (known.contains(key) || isNative(key) || isMapEntry(key)) {
                continue;
            }
            String reason = suggestion(key, known)
                    .map(s -> "unknown option; did you mean '" + s + "'?")
                    .orElse("unknown option");
            violations.add(invalid(key, node, String.valueOf(config.raw(key)), reason));
        }
        return violations;
    }

    private boolean isNative(String key) {
        return nativeNamespaces.stream().anyMatch(ns -> key.equals(ns) || key.startsWith(ns + "."));
    }

    private boolean isMapEntry(String key) {
        return options().stream().anyMatch(o -> o.type() == OptionType.MAP && key.startsWith(o.key() + "."));
    }

    private static Optional<String> suggestion(String key, Set<String> known) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : known) {
            int distance = distance(key, candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static OptionViolation missing(String option, String node) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("option", option);
        params.put("node", node);
        return new OptionViolation(ErrorCode.OPTION_REQUIRED, params);
    }

    private static OptionViolation invalid(String option, String node, String value, String reason) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("option", option);
        params.put("node", node);
        params.put("value", value);
        params.put("reason", reason);
        return new OptionViolation(ErrorCode.OPTION_INVALID, params);
    }

    /** Builder of {@link OptionRule}. */
    public static final class Builder {
        private final List<Option<?>> required = new ArrayList<>();
        private final List<Option<?>> optional = new ArrayList<>();
        private final List<List<Option<?>>> exclusive = new ArrayList<>();
        private final List<Condition> conditions = new ArrayList<>();
        private final List<String> nativeNamespaces = new ArrayList<>();

        private Builder() {}

        public Builder required(Option<?>... options) {
            required.addAll(List.of(options));
            return this;
        }

        public Builder optional(Option<?>... options) {
            optional.addAll(List.of(options));
            return this;
        }

        /** Exactly one of the options must be set. */
        public Builder exclusive(Option<?>... options) {
            if (options.length < 2) {
                throw new IllegalArgumentException("An exclusive group needs at least two options");
            }
            exclusive.add(List.of(options));
            return this;
        }

        /** When {@code trigger} equals {@code value}, the {@code then} options are required. */
        public <T> Builder conditional(Option<T> trigger, T value, Option<?>... then) {
            conditions.add(new Condition(trigger, value, List.of(then)));
            return this;
        }

        /** Prefixes of native configuration that is passed through, e.g. {@code jdbc.properties}. */
        public Builder nativeNamespaces(String... namespaces) {
            nativeNamespaces.addAll(List.of(namespaces));
            return this;
        }

        public OptionRule build() {
            Set<String> keys = new LinkedHashSet<>();
            for (Option<?> option : required) {
                if (!keys.add(option.key())) {
                    throw new IllegalArgumentException("Option declared twice: " + option.key());
                }
            }
            for (Option<?> option : optional) {
                if (!keys.add(option.key())) {
                    throw new IllegalArgumentException("Option declared twice: " + option.key());
                }
            }
            return new OptionRule(this);
        }
    }
}
