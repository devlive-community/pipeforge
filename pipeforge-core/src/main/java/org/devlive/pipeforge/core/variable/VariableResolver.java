/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.variable;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * Replaces the variables in the texts of a job.
 *
 * <table>
 *   <caption>Variables</caption>
 *   <tr><td>{@code ${var:name}}</td><td>a job variable: an override, else env.variables</td></tr>
 *   <tr><td>{@code ${date:format[,offset[,boundary]]}}</td><td>the scheduled time in the job time zone</td></tr>
 *   <tr><td>{@code ${env:NAME}}</td><td>an environment variable</td></tr>
 *   <tr><td>{@code ${run:id}}, {@code ${run:attempt}}, {@code ${run:start-time}}, {@code ${job:name}}</td>
 *       <td>the run and the job</td></tr>
 * </table>
 *
 * <p>{@code ${var:...}}, {@code ${env:...}}, {@code ${run:...}} and {@code ${job:...}} take a
 * default after {@code :-}. A doubled dollar sign, <code>$${</code>, writes a literal <code>${</code>. Texts in {@code ${...}}
 * with any other prefix, such as the {@code ${1}} of a routing rule, are left for the plugin.
 *
 * <p>Undefined variables throw {@link ErrorCode#VARIABLE_UNDEFINED}; malformed ones throw {@link
 * IllegalArgumentException} with the reason, which the caller reports with the location.
 */
public final class VariableResolver {

    /** Prefixes resolved by this class. */
    static final Set<String> PREFIXES = Set.of("var", "date", "env", "run", "job");

    /** Prefixes reserved for values that need a server: secrets, resource files, increments. */
    static final Set<String> SERVER_PREFIXES = Set.of("secret", "file", "incr");

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern OFFSET = Pattern.compile("([+-])(\\d+)(min|s|h|d|w|M|y)");
    private static final String DEFAULT_SEPARATOR = ":-";

    private final Map<String, String> definitions;
    private final VariableContext context;
    private final @Nullable ZoneId zone;
    private final @Nullable String jobName;
    private final Map<String, String> resolved = new LinkedHashMap<>();
    private final List<String> resolving = new ArrayList<>();

    /**
     * @param definitions env.variables as written: name to template
     * @param context overrides, scheduled time, environment and run
     * @param zone the job time zone, or {@code null} while it is being resolved itself
     * @param jobName the job name, or {@code null} while it is being resolved itself
     */
    public VariableResolver(
            Map<String, String> definitions, VariableContext context, @Nullable ZoneId zone, @Nullable String jobName) {
        this.definitions = new LinkedHashMap<>(definitions);
        this.context = context;
        this.zone = zone;
        this.jobName = jobName;
    }

    /** The job variables: every defined or overridden name with its value, definitions first. */
    public Map<String, String> variables() {
        Map<String, String> all = new LinkedHashMap<>();
        for (String name : definitions.keySet()) {
            all.put(name, variable(name, null));
        }
        for (String name : context.overrides().keySet()) {
            all.put(name, variable(name, null));
        }
        return all;
    }

    /** Replaces every variable in {@code text}. */
    public String resolve(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("$${", i)) {
                out.append("${");
                i += 3;
                continue;
            }
            if (!text.startsWith("${", i)) {
                out.append(text.charAt(i++));
                continue;
            }
            int end = text.indexOf('}', i + 2);
            String body = end < 0 ? text.substring(i + 2) : text.substring(i + 2, end);
            int colon = body.indexOf(':');
            String prefix = colon < 0 ? "" : body.substring(0, colon);
            if (!PREFIXES.contains(prefix) && !SERVER_PREFIXES.contains(prefix)) {
                out.append("${");
                i += 2;
                continue;
            }
            if (end < 0) {
                throw new IllegalArgumentException("'${" + prefix + ":' is not closed with '}'");
            }
            out.append(evaluate(prefix, body.substring(colon + 1)));
            i = end + 1;
        }
        return out.toString();
    }

    private String evaluate(String prefix, String body) {
        if (SERVER_PREFIXES.contains(prefix)) {
            throw new IllegalArgumentException("'${" + prefix + ":" + body + "}' is resolved by the PipeForge server"
                    + " when a job is deployed; it cannot be used when the job is planned locally");
        }
        if (prefix.equals("date")) {
            return date(body);
        }
        String name = body;
        String fallback = null;
        int separator = body.indexOf(DEFAULT_SEPARATOR);
        if (separator >= 0) {
            name = body.substring(0, separator);
            fallback = body.substring(separator + DEFAULT_SEPARATOR.length());
        }
        return switch (prefix) {
            case "var" -> {
                checkName(name, "variable");
                yield variable(name, fallback);
            }
            case "env" -> {
                checkName(name, "environment variable");
                String value = context.environment().get(name);
                yield value != null ? value : orUndefined(fallback, "env:" + name);
            }
            case "run" -> run(name, fallback);
            default -> job(name, fallback);
        };
    }

    private String variable(String name, @Nullable String fallback) {
        String cached = resolved.get(name);
        if (cached != null) {
            return cached;
        }
        String override = context.overrides().get(name);
        String template = override != null ? override : definitions.get(name);
        if (template == null) {
            return orUndefined(fallback, "var:" + name);
        }
        if (resolving.contains(name)) {
            List<String> cycle = new ArrayList<>(resolving.subList(resolving.indexOf(name), resolving.size()));
            cycle.add(name);
            throw new IllegalArgumentException("variables refer to each other: " + String.join(" -> ", cycle));
        }
        resolving.add(name);
        try {
            String value = resolve(template);
            resolved.put(name, value);
            return value;
        } finally {
            resolving.remove(resolving.size() - 1);
        }
    }

    private String run(String name, @Nullable String fallback) {
        RunInfo run = context.run();
        return switch (name) {
            case "id" -> run.id();
            case "attempt" -> String.valueOf(run.attempt());
            case "start-time" -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                    run.startTime().atZone(requireZone("${run:start-time}")).truncatedTo(ChronoUnit.MILLIS));
            default -> {
                if (fallback != null) {
                    yield fallback;
                }
                throw new IllegalArgumentException(
                        "unknown run variable '" + name + "'; use ${run:id}, ${run:attempt} or ${run:start-time}");
            }
        };
    }

    private String job(String name, @Nullable String fallback) {
        if (!name.equals("name")) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("unknown job variable '" + name + "'; use ${job:name}");
        }
        if (jobName == null) {
            throw new IllegalArgumentException("${job:name} cannot be used in the job name or the time zone");
        }
        return jobName;
    }

    private String date(String body) {
        String[] parts = body.split(",", -1);
        if (parts.length > 3) {
            throw new IllegalArgumentException(
                    "${date:" + body + "} has too many parts; write ${date:format[,offset[,boundary]]}");
        }
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(parts[0], Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid date format '" + parts[0] + "': " + e.getMessage(), e);
        }
        ZonedDateTime time = context.scheduledTime().atZone(requireZone("${date:...}"));
        if (parts.length > 1) {
            time = offset(time, parts[1].trim());
        }
        if (parts.length > 2) {
            time = boundary(time, parts[2].trim());
        }
        try {
            return formatter.format(time);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("invalid date format '" + parts[0] + "': " + e.getMessage(), e);
        }
    }

    /** Applies offsets such as {@code -1d}, {@code +2h} or {@code -1M+3d}; an empty text is no offset. */
    static ZonedDateTime offset(ZonedDateTime time, String text) {
        if (text.isEmpty()) {
            return time;
        }
        Matcher matcher = OFFSET.matcher(text);
        int position = 0;
        ZonedDateTime result = time;
        while (position < text.length()) {
            if (!matcher.find(position) || matcher.start() != position) {
                throw new IllegalArgumentException("invalid date offset '" + text
                        + "'; write a signed number and a unit (s, min, h, d, w, M, y), e.g. -1d or -1M+3d");
            }
            long amount = Long.parseLong(matcher.group(2));
            if (matcher.group(1).equals("-")) {
                amount = -amount;
            }
            result = switch (matcher.group(3)) {
                case "s" -> result.plusSeconds(amount);
                case "min" -> result.plusMinutes(amount);
                case "h" -> result.plusHours(amount);
                case "d" -> result.plusDays(amount);
                case "w" -> result.plusWeeks(amount);
                case "M" -> result.plusMonths(amount);
                default -> result.plusYears(amount);
            };
            position = matcher.end();
        }
        return result;
    }

    /** Moves to the start or end of the day, week (Monday to Sunday) or month. */
    static ZonedDateTime boundary(ZonedDateTime time, String name) {
        return switch (name) {
            case "" -> time;
            case "start-of-day" -> time.truncatedTo(ChronoUnit.DAYS);
            case "end-of-day" -> time.with(LocalTime.MAX);
            case "start-of-week" -> time.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS);
            case "start-of-month" -> time.with(TemporalAdjusters.firstDayOfMonth())
                    .truncatedTo(ChronoUnit.DAYS);
            case "end-of-month" -> time.with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX);
            default -> throw new IllegalArgumentException("unknown boundary '" + name
                    + "'; use start-of-day, end-of-day, start-of-week, start-of-month or end-of-month");
        };
    }

    private ZoneId requireZone(String what) {
        if (zone == null) {
            throw new IllegalArgumentException(what + " cannot be used in the time zone itself");
        }
        return zone;
    }

    private static void checkName(String name, String what) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid " + what + " name '" + name + "'");
        }
    }

    private static String orUndefined(@Nullable String fallback, String variable) {
        if (fallback != null) {
            return fallback;
        }
        throw PipeForgeException.of(ErrorCode.VARIABLE_UNDEFINED)
                .param("variable", variable)
                .build();
    }
}
