/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function.builtin;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.devlive.pipeforge.expressions.EvaluationContext;
import org.devlive.pipeforge.expressions.function.Scalar;
import org.devlive.pipeforge.expressions.runtime.ExprError;
import org.devlive.pipeforge.expressions.runtime.R;

/**
 * Date and time functions. Local dates and times are interpreted in the job time zone; patterns
 * use the {@link DateTimeFormatter} letters ({@code yyyy-MM-dd HH:mm:ss}).
 */
public final class DateTimeFunctions {

    private static final int FORMATTER_CACHE = 256;

    private static final Map<String, DateTimeFormatter> FORMATTERS =
            Collections.synchronizedMap(new LinkedHashMap<String, DateTimeFormatter>(32, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DateTimeFormatter> eldest) {
                    return size() > FORMATTER_CACHE;
                }
            });

    private DateTimeFunctions() {}

    static DateTimeFormatter formatter(String pattern) {
        DateTimeFormatter formatter = FORMATTERS.get(pattern);
        if (formatter == null) {
            try {
                // Strict resolution rejects dates such as 2026-11-31 instead of clamping them; the
                // era default keeps year-of-era patterns (yyyy) working under strict resolution.
                formatter = new DateTimeFormatterBuilder()
                        .appendPattern(pattern)
                        .parseDefaulting(ChronoField.ERA, 1)
                        .toFormatter(Locale.ROOT)
                        .withResolverStyle(ResolverStyle.STRICT);
            } catch (IllegalArgumentException e) {
                throw new ExprError("invalid date pattern '" + pattern + "': " + e.getMessage());
            }
            FORMATTERS.put(pattern, formatter);
        }
        return formatter;
    }

    private static LocalDateTime local(EvaluationContext ctx, Instant value) {
        return LocalDateTime.ofInstant(value, ctx.timeZone());
    }

    /** Units accepted by DATE_ADD, TIMESTAMPDIFF, DATE_TRUNC and EXTRACT. */
    static ChronoUnit unit(String unit) {
        return switch (unit.trim().toUpperCase(Locale.ROOT).replaceAll("S$", "")) {
            case "MICROSECOND" -> ChronoUnit.MICROS;
            case "MILLISECOND" -> ChronoUnit.MILLIS;
            case "SECOND" -> ChronoUnit.SECONDS;
            case "MINUTE" -> ChronoUnit.MINUTES;
            case "HOUR" -> ChronoUnit.HOURS;
            case "DAY" -> ChronoUnit.DAYS;
            case "WEEK" -> ChronoUnit.WEEKS;
            case "MONTH" -> ChronoUnit.MONTHS;
            case "QUARTER" -> ChronoUnit.MONTHS;
            case "YEAR" -> ChronoUnit.YEARS;
            default -> throw new ExprError(
                    "unknown time unit '" + unit + "'; use SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, QUARTER or YEAR");
        };
    }

    private static boolean isQuarter(String unit) {
        return unit.trim().toUpperCase(Locale.ROOT).replaceAll("S$", "").equals("QUARTER");
    }

    // ------------------------------------------------------------------ current time

    @Scalar(name = "CURRENT_DATE", deterministic = false)
    public static LocalDate currentDate(EvaluationContext ctx) {
        return LocalDate.ofInstant(ctx.now(), ctx.timeZone());
    }

    /** The processing time; evaluated for every row. */
    @Scalar(name = "CURRENT_TIMESTAMP", returns = "TIMESTAMP_LTZ(3)", deterministic = false)
    public static Instant currentTimestamp(EvaluationContext ctx) {
        return ctx.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Scalar(name = "NOW", returns = "TIMESTAMP_LTZ(3)", deterministic = false)
    public static Instant now(EvaluationContext ctx) {
        return currentTimestamp(ctx);
    }

    /** Start time of the run; the same for every row, retry and restore of the run. */
    @Scalar(name = "RUN_START_TIME", returns = "TIMESTAMP_LTZ(3)")
    public static Instant runStartTime(EvaluationContext ctx) {
        return ctx.runStartTime().truncatedTo(ChronoUnit.MILLIS);
    }

    // ------------------------------------------------------------------ formatting and parsing

    @Scalar(name = "DATE_FORMAT")
    public static String dateFormat(LocalDate value, String pattern) {
        return format(value.atStartOfDay(), pattern);
    }

    @Scalar(name = "DATE_FORMAT")
    public static String dateFormat(LocalDateTime value, String pattern) {
        return format(value, pattern);
    }

    @Scalar(name = "DATE_FORMAT")
    public static String dateFormat(EvaluationContext ctx, Instant value, String pattern) {
        try {
            return formatter(pattern).format(value.atZone(ctx.timeZone()));
        } catch (DateTimeException e) {
            throw new ExprError("cannot format with '" + pattern + "': " + e.getMessage());
        }
    }

    private static String format(LocalDateTime value, String pattern) {
        try {
            return formatter(pattern).format(value);
        } catch (DateTimeException e) {
            throw new ExprError("cannot format with '" + pattern + "': " + e.getMessage());
        }
    }

    @Scalar(name = "TO_DATE")
    public static LocalDate toDate(String text) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new ExprError("'" + text + "' is not a date (yyyy-MM-dd)");
        }
    }

    @Scalar(name = "TO_DATE")
    public static LocalDate toDate(String text, String pattern) {
        try {
            return LocalDate.parse(text.trim(), formatter(pattern));
        } catch (DateTimeParseException e) {
            throw new ExprError("'" + text + "' does not match the pattern '" + pattern + "'");
        }
    }

    @Scalar(name = "TO_TIMESTAMP", returns = "TIMESTAMP(3)")
    public static LocalDateTime toTimestamp(String text) {
        try {
            return R.parseTimestamp(text).truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            throw new ExprError("'" + text + "' is not a timestamp (yyyy-MM-dd HH:mm:ss)");
        }
    }

    @Scalar(name = "TO_TIMESTAMP", returns = "TIMESTAMP(3)")
    public static LocalDateTime toTimestamp(String text, String pattern) {
        try {
            DateTimeFormatter formatter = formatter(pattern);
            var parsed = formatter.parseBest(text.trim(), LocalDateTime::from, LocalDate::from);
            LocalDateTime value = parsed instanceof LocalDate date ? date.atStartOfDay() : (LocalDateTime) parsed;
            return value.truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            throw new ExprError("'" + text + "' does not match the pattern '" + pattern + "'");
        }
    }

    /** An instant; text without an offset is local time in the job time zone. */
    @Scalar(name = "TO_TIMESTAMP_LTZ", returns = "TIMESTAMP_LTZ(3)")
    public static Instant toTimestampLtz(EvaluationContext ctx, String text) {
        try {
            return R.parseInstant(text, ctx.timeZone()).truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            throw new ExprError("'" + text + "' is not a timestamp");
        }
    }

    @Scalar(name = "TO_TIMESTAMP_LTZ", returns = "TIMESTAMP_LTZ(3)")
    public static Instant toTimestampLtz(EvaluationContext ctx, String text, String pattern) {
        return toTimestamp(text, pattern).atZone(ctx.timeZone()).toInstant();
    }

    @Scalar(name = "FROM_UNIXTIME", returns = "TIMESTAMP_LTZ(0)")
    public static Instant fromUnixtime(long seconds) {
        return Instant.ofEpochSecond(seconds);
    }

    @Scalar(name = "UNIX_TIMESTAMP", deterministic = false)
    public static long unixTimestamp(EvaluationContext ctx) {
        return ctx.now().getEpochSecond();
    }

    @Scalar(name = "UNIX_TIMESTAMP")
    public static long unixTimestamp(EvaluationContext ctx, LocalDateTime value) {
        return value.atZone(ctx.timeZone()).toEpochSecond();
    }

    @Scalar(name = "UNIX_TIMESTAMP")
    public static long unixTimestamp(Instant value) {
        return value.getEpochSecond();
    }

    // ------------------------------------------------------------------ fields

    @Scalar(name = "YEAR")
    public static int year(LocalDateTime value) {
        return value.getYear();
    }

    @Scalar(name = "YEAR")
    public static int year(EvaluationContext ctx, Instant value) {
        return year(local(ctx, value));
    }

    @Scalar(name = "QUARTER")
    public static int quarter(LocalDateTime value) {
        return (value.getMonthValue() - 1) / 3 + 1;
    }

    @Scalar(name = "QUARTER")
    public static int quarter(EvaluationContext ctx, Instant value) {
        return quarter(local(ctx, value));
    }

    @Scalar(name = "MONTH")
    public static int month(LocalDateTime value) {
        return value.getMonthValue();
    }

    @Scalar(name = "MONTH")
    public static int month(EvaluationContext ctx, Instant value) {
        return month(local(ctx, value));
    }

    /** ISO week of the year (weeks start on Monday). */
    @Scalar(name = "WEEK")
    public static int week(LocalDateTime value) {
        return value.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    @Scalar(name = "WEEK")
    public static int week(EvaluationContext ctx, Instant value) {
        return week(local(ctx, value));
    }

    /** Day of the month; also written DAY(x). */
    @Scalar(name = "DAYOFMONTH")
    public static int dayOfMonth(LocalDateTime value) {
        return value.getDayOfMonth();
    }

    @Scalar(name = "DAYOFMONTH")
    public static int dayOfMonth(EvaluationContext ctx, Instant value) {
        return dayOfMonth(local(ctx, value));
    }

    /** 1 = Sunday, 2 = Monday, ..., 7 = Saturday. */
    @Scalar(name = "DAYOFWEEK")
    public static int dayOfWeek(LocalDateTime value) {
        return value.getDayOfWeek().getValue() % 7 + 1;
    }

    @Scalar(name = "DAYOFWEEK")
    public static int dayOfWeek(EvaluationContext ctx, Instant value) {
        return dayOfWeek(local(ctx, value));
    }

    @Scalar(name = "DAYOFYEAR")
    public static int dayOfYear(LocalDateTime value) {
        return value.getDayOfYear();
    }

    @Scalar(name = "DAYOFYEAR")
    public static int dayOfYear(EvaluationContext ctx, Instant value) {
        return dayOfYear(local(ctx, value));
    }

    @Scalar(name = "HOUR")
    public static int hour(LocalTime value) {
        return value.getHour();
    }

    @Scalar(name = "HOUR")
    public static int hour(LocalDateTime value) {
        return value.getHour();
    }

    @Scalar(name = "HOUR")
    public static int hour(EvaluationContext ctx, Instant value) {
        return hour(local(ctx, value));
    }

    @Scalar(name = "MINUTE")
    public static int minute(LocalTime value) {
        return value.getMinute();
    }

    @Scalar(name = "MINUTE")
    public static int minute(LocalDateTime value) {
        return value.getMinute();
    }

    @Scalar(name = "MINUTE")
    public static int minute(EvaluationContext ctx, Instant value) {
        return minute(local(ctx, value));
    }

    @Scalar(name = "SECOND")
    public static int second(LocalTime value) {
        return value.getSecond();
    }

    @Scalar(name = "SECOND")
    public static int second(LocalDateTime value) {
        return value.getSecond();
    }

    @Scalar(name = "SECOND")
    public static int second(EvaluationContext ctx, Instant value) {
        return second(local(ctx, value));
    }

    // ------------------------------------------------------------------ arithmetic

    @Scalar(name = "DATE_ADD")
    public static LocalDate dateAdd(LocalDate value, long days) {
        return value.plusDays(days);
    }

    /** Adds {@code amount} units to a date; units smaller than a day are rejected. */
    @Scalar(name = "DATE_ADD")
    public static LocalDate dateAdd(LocalDate value, long amount, String unit) {
        ChronoUnit chrono = unit(unit);
        if (chrono.getDuration().compareTo(ChronoUnit.DAYS.getDuration()) < 0) {
            throw new ExprError("cannot add " + unit + " to a DATE; cast it to TIMESTAMP first");
        }
        return isQuarter(unit) ? value.plusMonths(3 * amount) : value.plus(amount, chrono);
    }

    @Scalar(name = "DATE_ADD", returns = "$0")
    public static LocalDateTime dateAdd(LocalDateTime value, long amount, String unit) {
        return isQuarter(unit) ? value.plusMonths(3 * amount) : value.plus(amount, unit(unit));
    }

    @Scalar(name = "DATE_ADD", returns = "$0")
    public static Instant dateAdd(EvaluationContext ctx, Instant value, long amount, String unit) {
        return dateAdd(local(ctx, value), amount, unit).atZone(ctx.timeZone()).toInstant();
    }

    @Scalar(name = "DATE_SUB")
    public static LocalDate dateSub(LocalDate value, long days) {
        return value.minusDays(days);
    }

    @Scalar(name = "DATE_SUB")
    public static LocalDate dateSub(LocalDate value, long amount, String unit) {
        return dateAdd(value, Math.negateExact(amount), unit);
    }

    @Scalar(name = "DATE_SUB", returns = "$0")
    public static LocalDateTime dateSub(LocalDateTime value, long amount, String unit) {
        return dateAdd(value, Math.negateExact(amount), unit);
    }

    @Scalar(name = "DATE_SUB", returns = "$0")
    public static Instant dateSub(EvaluationContext ctx, Instant value, long amount, String unit) {
        return dateAdd(ctx, value, Math.negateExact(amount), unit);
    }

    /** Days from {@code start} to {@code end} (calendar dates). */
    @Scalar(name = "DATEDIFF")
    public static long dateDiff(LocalDateTime end, LocalDateTime start) {
        return ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
    }

    /** Whole {@code unit}s from {@code start} to {@code end}. */
    @Scalar(name = "TIMESTAMPDIFF")
    public static long timestampDiff(String unit, LocalDateTime start, LocalDateTime end) {
        if (isQuarter(unit)) {
            return ChronoUnit.MONTHS.between(start, end) / 3;
        }
        return unit(unit).between(start, end);
    }

    @Scalar(name = "TIMESTAMPDIFF")
    public static long timestampDiff(EvaluationContext ctx, String unit, Instant start, Instant end) {
        return timestampDiff(unit, local(ctx, start), local(ctx, end));
    }

    @Scalar(name = "DATE_TRUNC")
    public static LocalDate dateTrunc(String unit, LocalDate value) {
        return dateTrunc(unit, value.atStartOfDay()).toLocalDate();
    }

    /** Truncates to the start of the year, quarter, month, week (Monday), day, hour, minute or second. */
    @Scalar(name = "DATE_TRUNC", returns = "$1")
    public static LocalDateTime dateTrunc(String unit, LocalDateTime value) {
        String name = unit.trim().toUpperCase(Locale.ROOT).replaceAll("S$", "");
        LocalDateTime day = value.truncatedTo(ChronoUnit.DAYS);
        return switch (name) {
            case "YEAR" -> day.withDayOfYear(1);
            case "QUARTER" -> day.withDayOfMonth(1).withMonth((value.getMonthValue() - 1) / 3 * 3 + 1);
            case "MONTH" -> day.withDayOfMonth(1);
            case "WEEK" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "DAY" -> day;
            case "HOUR", "MINUTE", "SECOND", "MILLISECOND", "MICROSECOND" -> value.truncatedTo(unit(name));
            default -> throw new ExprError("unknown time unit '" + unit + "'");
        };
    }

    @Scalar(name = "DATE_TRUNC", returns = "$1")
    public static Instant dateTrunc(EvaluationContext ctx, String unit, Instant value) {
        return dateTrunc(unit, local(ctx, value)).atZone(ctx.timeZone()).toInstant();
    }

    @Scalar(name = "LAST_DAY")
    public static LocalDate lastDay(LocalDateTime value) {
        return value.toLocalDate().with(TemporalAdjusters.lastDayOfMonth());
    }

    /** Converts a local time from one time zone to another. */
    @Scalar(name = "CONVERT_TZ", returns = "$0")
    public static LocalDateTime convertTz(LocalDateTime value, String from, String to) {
        return value.atZone(zone(from)).withZoneSameInstant(zone(to)).toLocalDateTime();
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id.trim());
        } catch (DateTimeException e) {
            throw new ExprError("unknown time zone '" + id + "'");
        }
    }
}
