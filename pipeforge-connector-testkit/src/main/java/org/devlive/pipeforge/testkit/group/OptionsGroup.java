/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.OptionType;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.explain.PlanExplainer;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.OptionOrigin;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.core.plan.PlannedOption;
import org.devlive.pipeforge.testkit.TestJob;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicNode;
import org.opentest4j.AssertionFailedError;

/**
 * The {@code options} group: checks that the planner applies the component's option rule. The
 * cases are derived from the rule itself, starting from options the connector says are valid:
 *
 * <ul>
 *   <li>the valid options plan; unknown options are rejected;
 *   <li>every required option without a default is reported missing ({@code PF-CONF-0001});
 *   <li>defaults appear in the plan with origin {@code default};
 *   <li>values below the minimum, above the maximum or outside the choices are rejected ({@code
 *       PF-CONF-0002});
 *   <li>old option names still work;
 *   <li>exclusive options cannot be set together, and one of them is required;
 *   <li>conditional options are required when their condition holds;
 *   <li>sensitive values are masked in the plan and its text.
 * </ul>
 */
public final class OptionsGroup {

    private static final String UNKNOWN = "testkit-unknown-option";

    private OptionsGroup() {}

    /**
     * @param factory the component under test
     * @param node the id of its node in the jobs that {@code job} builds
     * @param baseline options the component accepts
     * @param job builds a job with the component's node configured with the given options
     */
    public static List<DynamicNode> cases(
            Factory factory, String node, Map<String, Object> baseline, Function<Map<String, Object>, TestJob> job) {
        OptionRule rule = factory.optionRule();
        List<DynamicNode> cases = new ArrayList<>();
        cases.add(
                Cases.test("plans with valid options", () -> job.apply(baseline).plan()));
        cases.add(Cases.test("rejects an unknown option", () -> {
            Map<String, Object> options = new LinkedHashMap<>(baseline);
            options.put(UNKNOWN, "x");
            expectError(job.apply(options), ErrorCode.OPTION_INVALID, UNKNOWN);
        }));
        for (Option<?> option : rule.required()) {
            if (!option.hasDefault()) {
                cases.add(Cases.test(
                        "requires " + option.key(),
                        () -> expectError(
                                job.apply(without(baseline, option)), ErrorCode.OPTION_REQUIRED, option.key())));
            }
        }
        for (Option<?> option : rule.options()) {
            if (option.hasDefault() && !present(baseline, option)) {
                cases.add(Cases.test("defaults " + option.key() + " to " + option.defaultValue(), () -> {
                    PlannedNode planned = plannedNode(job.apply(baseline).plan(), node);
                    PlannedOption value = planned.options().stream()
                            .filter(o -> o.key().equals(option.key()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionFailedError("The plan has no value for " + option.key()));
                    assertEquals(OptionOrigin.DEFAULT, value.origin(), "origin of " + option.key());
                    if (!option.isSensitive()) {
                        assertEquals(option.defaultValue(), value.value(), "default of " + option.key());
                    }
                }));
            }
            addRangeCases(cases, baseline, job, option);
            if (option.type() == OptionType.ENUM) {
                cases.add(Cases.test(
                        "rejects an unknown choice of " + option.key(),
                        () -> expectError(
                                job.apply(with(baseline, option, "testkit-no-such-choice")),
                                ErrorCode.OPTION_INVALID,
                                option.key())));
            }
            for (String old : option.deprecatedKeys()) {
                cases.add(Cases.test("accepts the old name " + old + " of " + option.key(), () -> {
                    Map<String, Object> options = without(baseline, option);
                    options.put(old, value(baseline, option));
                    PlannedNode planned = plannedNode(job.apply(options).plan(), node);
                    assertNotNull(planned.config().get(option), option.key() + " read from " + old);
                }));
            }
            if (option.isSensitive() && option.type() == OptionType.STRING) {
                cases.add(Cases.test("masks the sensitive option " + option.key(), () -> {
                    String secret =
                            present(baseline, option) ? String.valueOf(value(baseline, option)) : "testkit-secret-7f3a";
                    JobPlan plan = job.apply(with(baseline, option, secret)).plan();
                    PlannedOption value = plannedNode(plan, node).options().stream()
                            .filter(o -> o.key().equals(option.key())
                                    || option.deprecatedKeys().contains(o.key()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionFailedError("The plan has no value for " + option.key()));
                    assertEquals(OptionRule.MASK, value.value(), "value of " + option.key() + " in the plan");
                    if (secret.length() >= 6) {
                        assertFalse(PlanExplainer.text(plan).contains(secret), "explain shows " + option.key());
                        assertFalse(PlanExplainer.json(plan).contains(secret), "the JSON plan shows " + option.key());
                    }
                }));
            }
        }
        for (List<Option<?>> group : rule.exclusive()) {
            String keys = String.join(" | ", group.stream().map(Option::key).toList());
            cases.add(Cases.test("rejects " + keys + " together", () -> {
                Map<String, Object> options = new LinkedHashMap<>(baseline);
                for (Option<?> member : group) {
                    options = with(options, member, value(baseline, member));
                }
                expectError(job.apply(options), ErrorCode.OPTION_INVALID, keys);
            }));
            cases.add(Cases.test("requires one of " + keys, () -> {
                Map<String, Object> options = new LinkedHashMap<>(baseline);
                for (Option<?> member : group) {
                    options = without(options, member);
                }
                expectError(job.apply(options), ErrorCode.OPTION_REQUIRED, keys);
            }));
        }
        for (OptionRule.Condition condition : rule.conditions()) {
            for (Option<?> required : condition.required()) {
                if (required.hasDefault()) {
                    continue;
                }
                String trigger = condition.trigger().key();
                cases.add(Cases.test(
                        "requires " + required.key() + " when " + trigger + " is " + condition.value(), () -> {
                            Map<String, Object> options =
                                    with(without(baseline, required), condition.trigger(), written(condition.value()));
                            expectError(job.apply(options), ErrorCode.OPTION_REQUIRED, required.key());
                        }));
            }
        }
        return cases;
    }

    private static void addRangeCases(
            List<DynamicNode> cases,
            Map<String, Object> baseline,
            Function<Map<String, Object>, TestJob> job,
            Option<?> option) {
        Double min = option.min();
        Double max = option.max();
        if (min != null) {
            Object below = outside(option, min, -1);
            cases.add(Cases.test(
                    "rejects " + option.key() + " = " + below + " below its minimum",
                    () -> expectError(
                            job.apply(with(baseline, option, below)), ErrorCode.OPTION_INVALID, option.key())));
        }
        if (max != null) {
            Object above = outside(option, max, 1);
            cases.add(Cases.test(
                    "rejects " + option.key() + " = " + above + " above its maximum",
                    () -> expectError(
                            job.apply(with(baseline, option, above)), ErrorCode.OPTION_INVALID, option.key())));
        }
    }

    private static Object outside(Option<?> option, double bound, int direction) {
        return switch (option.type()) {
            case INT -> (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) bound + direction));
            case LONG -> (long) bound + direction;
            default -> bound + direction;
        };
    }

    /** Expects planning to fail with {@code code} for the option {@code key}. */
    static void expectError(TestJob job, ErrorCode code, String key) {
        try {
            job.plan();
        } catch (PipeForgeException e) {
            assertEquals(code, e.code(), () -> "Expected " + code + " but got " + e.getMessage());
            Object option = e.params().get("option");
            assertTrue(
                    key.equals(option),
                    () -> "Expected the error to name option '" + key + "' but got " + e.getMessage());
            return;
        }
        throw new AssertionFailedError("Expected " + code + " for option '" + key + "' but the job planned");
    }

    private static PlannedNode plannedNode(JobPlan plan, String node) {
        return plan.node(node).orElseThrow(() -> new AssertionFailedError("The plan has no node " + node));
    }

    private static boolean present(Map<String, Object> options, Option<?> option) {
        return options.containsKey(option.key())
                || option.deprecatedKeys().stream().anyMatch(options::containsKey);
    }

    private static Map<String, Object> without(Map<String, Object> options, Option<?> option) {
        Map<String, Object> result = new LinkedHashMap<>(options);
        result.remove(option.key());
        option.deprecatedKeys().forEach(result::remove);
        return result;
    }

    private static Map<String, Object> with(Map<String, Object> options, Option<?> option, Object value) {
        Map<String, Object> result = without(options, option);
        result.put(option.key(), value);
        return result;
    }

    /** The value of {@code option} in the baseline, or a valid sample value of its type. */
    private static Object value(Map<String, Object> baseline, Option<?> option) {
        if (baseline.containsKey(option.key())) {
            return baseline.get(option.key());
        }
        for (String old : option.deprecatedKeys()) {
            if (baseline.containsKey(old)) {
                return baseline.get(old);
            }
        }
        return sample(option);
    }

    static Object sample(Option<?> option) {
        @Nullable Double min = option.min();
        @Nullable Double max = option.max();
        double number = min != null ? min : max != null ? Math.min(1, max) : 1;
        return switch (option.type()) {
            case STRING -> "testkit";
            case INT -> (int) Math.ceil(number);
            case LONG -> (long) Math.ceil(number);
            case DOUBLE -> number;
            case BOOLEAN -> true;
            case DURATION -> "1s";
            case ENUM -> option.enumValues().isEmpty()
                    ? "testkit"
                    : option.enumValues().get(0);
            case LIST -> List.of("testkit");
            case MAP -> Map.of("key", "value");
        };
    }

    /** A value as a job would write it: enum constants by name, everything else as is. */
    private static Object written(Object value) {
        return value instanceof Enum<?> constant ? constant.name() : value;
    }
}
