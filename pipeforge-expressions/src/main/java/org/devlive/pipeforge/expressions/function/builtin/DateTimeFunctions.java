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
import org.devlive.pipeforge.api.function.FunctionContext;
import org.devlive.pipeforge.api.function.Returns;
import org.devlive.pipeforge.api.function.ScalarFunction;
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

    private static LocalDateTime local(FunctionContext ctx, Instant value) {
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

    @ScalarFunction(name = "CURRENT_DATE", deterministic = false)
    public static LocalDate currentDate(FunctionContext ctx) {
        return LocalDate.ofInstant(ctx.now(), ctx.timeZone());
    }

    /** The processing time; evaluated for every row. */
    @Returns("TIMESTAMP_LTZ(3)")
    @ScalarFunction(name = "CURRENT_TIMESTAMP", deterministic = false)
    public static Instant currentTimestamp(FunctionContext ctx) {
        return ctx.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Returns("TIMESTAMP_LTZ(3)")
    @ScalarFunction(name = "NOW", deterministic = false)
    public static Instant now(FunctionContext ctx) {
        return currentTimestamp(ctx);
    }

    /** Start time of the run; the same for every row, retry and restore of the run. */
    @Returns("TIMESTAMP_LTZ(3)")
    @ScalarFunction(name = "RUN_START_TIME")
    public static Instant runStartTime(FunctionContext ctx) {
        return ctx.runStartTime().truncatedTo(ChronoUnit.MILLIS);
    }

    // ------------------------------------------------------------------ formatting and parsing

    @ScalarFunction(name = "DATE_FORMAT")
    public static String dateFormat(LocalDate value, String pattern) {
        return format(value.atStartOfDay(), pattern);
    }

    @ScalarFunction(name = "DATE_FORMAT")
    public static String dateFormat(LocalDateTime value, String pattern) {
        return format(value, pattern);
    }

    @ScalarFunction(name = "DATE_FORMAT")
    public static String dateFormat(FunctionContext ctx, Instant value, String pattern) {
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

    @ScalarFunction(name = "TO_DATE")
    public static LocalDate toDate(String text) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new ExprError("'" + text + "' is not a date (yyyy-MM-dd)");
        }
    }

    @ScalarFunction(name = "TO_DATE")
    public static LocalDate toDate(String text, String pattern) {
        try {
            return LocalDate.parse(text.trim(), formatter(pattern));
        } catch (DateTimeParseException e) {
            throw new ExprError("'" + text + "' does not match the pattern '" + pattern + "'");
        }
    }

    @Returns("TIMESTAMP(3)")
    @ScalarFunction(name = "TO_TIMESTAMP")
    public static LocalDateTime toTimestamp(String text) {
        try {
            return R.parseTimestamp(text).truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            throw new ExprError("'" + text + "' is not a timestamp (yyyy-MM-dd HH:mm:ss)");
        }
    }

    @Returns("TIMESTAMP(3)")
    @ScalarFunction(name = "TO_TIMESTAMP")
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
    @Returns("TIMESTAMP_LTZ(3)")
    @ScalarFunction(name = "TO_TIMESTAMP_LTZ")
    public static Instant toTimestampLtz(FunctionContext ctx, String text) {
        try {
            return R.parseInstant(text, ctx.timeZone()).truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            throw new ExprError("'" + text + "' is not a timestamp");
        }
    }

    @Returns("TIMESTAMP_LTZ(3)")
    @ScalarFunction(name = "TO_TIMESTAMP_LTZ")
    public static Instant toTimestampLtz(FunctionContext ctx, String text, String pattern) {
        return toTimestamp(text, pattern).atZone(ctx.timeZone()).toInstant();
    }

    @Returns("TIMESTAMP_LTZ(0)")
    @ScalarFunction(name = "FROM_UNIXTIME")
    public static Instant fromUnixtime(long seconds) {
        return Instant.ofEpochSecond(seconds);
    }

    @ScalarFunction(name = "UNIX_TIMESTAMP", deterministic = false)
    public static long unixTimestamp(FunctionContext ctx) {
        return ctx.now().getEpochSecond();
    }

    @ScalarFunction(name = "UNIX_TIMESTAMP")
    public static long unixTimestamp(FunctionContext ctx, LocalDateTime value) {
        return value.atZone(ctx.timeZone()).toEpochSecond();
    }

    @ScalarFunction(name = "UNIX_TIMESTAMP")
    public static long unixTimestamp(Instant value) {
        return value.getEpochSecond();
    }

    // ------------------------------------------------------------------ fields

    @ScalarFunction(name = "YEAR")
    public static int year(LocalDateTime value) {
        return value.getYear();
    }

    @ScalarFunction(name = "YEAR")
    public static int year(FunctionContext ctx, Instant value) {
        return year(local(ctx, value));
    }

    @ScalarFunction(name = "QUARTER")
    public static int quarter(LocalDateTime value) {
        return (value.getMonthValue() - 1) / 3 + 1;
    }

    @ScalarFunction(name = "QUARTER")
    public static int quarter(FunctionContext ctx, Instant value) {
        return quarter(local(ctx, value));
    }

    @ScalarFunction(name = "MONTH")
    public static int month(LocalDateTime value) {
        return value.getMonthValue();
    }

    @ScalarFunction(name = "MONTH")
    public static int month(FunctionContext ctx, Instant value) {
        return month(local(ctx, value));
    }

    /** ISO week of the year (weeks start on Monday). */
    @ScalarFunction(name = "WEEK")
    public static int week(LocalDateTime value) {
        return value.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    @ScalarFunction(name = "WEEK")
    public static int week(FunctionContext ctx, Instant value) {
        return week(local(ctx, value));
    }

    /** Day of the month; also written DAY(x). */
    @ScalarFunction(name = "DAYOFMONTH")
    public static int dayOfMonth(LocalDateTime value) {
        return value.getDayOfMonth();
    }

    @ScalarFunction(name = "DAYOFMONTH")
    public static int dayOfMonth(FunctionContext ctx, Instant value) {
        return dayOfMonth(local(ctx, value));
    }

    /** 1 = Sunday, 2 = Monday, ..., 7 = Saturday. */
    @ScalarFunction(name = "DAYOFWEEK")
    public static int dayOfWeek(LocalDateTime value) {
        return value.getDayOfWeek().getValue() % 7 + 1;
    }

    @ScalarFunction(name = "DAYOFWEEK")
    public static int dayOfWeek(FunctionContext ctx, Instant value) {
        return dayOfWeek(local(ctx, value));
    }

    @ScalarFunction(name = "DAYOFYEAR")
    public static int dayOfYear(LocalDateTime value) {
        return value.getDayOfYear();
    }

    @ScalarFunction(name = "DAYOFYEAR")
    public static int dayOfYear(FunctionContext ctx, Instant value) {
        return dayOfYear(local(ctx, value));
    }

    @ScalarFunction(name = "HOUR")
    public static int hour(LocalTime value) {
        return value.getHour();
    }

    @ScalarFunction(name = "HOUR")
    public static int hour(LocalDateTime value) {
        return value.getHour();
    }

    @ScalarFunction(name = "HOUR")
    public static int hour(FunctionContext ctx, Instant value) {
        return hour(local(ctx, value));
    }

    @ScalarFunction(name = "MINUTE")
    public static int minute(LocalTime value) {
        return value.getMinute();
    }

    @ScalarFunction(name = "MINUTE")
    public static int minute(LocalDateTime value) {
        return value.getMinute();
    }

    @ScalarFunction(name = "MINUTE")
    public static int minute(FunctionContext ctx, Instant value) {
        return minute(local(ctx, value));
    }

    @ScalarFunction(name = "SECOND")
    public static int second(LocalTime value) {
        return value.getSecond();
    }

    @ScalarFunction(name = "SECOND")
    public static int second(LocalDateTime value) {
        return value.getSecond();
    }

    @ScalarFunction(name = "SECOND")
    public static int second(FunctionContext ctx, Instant value) {
        return second(local(ctx, value));
    }

    // ------------------------------------------------------------------ arithmetic

    @ScalarFunction(name = "DATE_ADD")
    public static LocalDate dateAdd(LocalDate value, long days) {
        return value.plusDays(days);
    }

    /** Adds {@code amount} units to a date; units smaller than a day are rejected. */
    @ScalarFunction(name = "DATE_ADD")
    public static LocalDate dateAdd(LocalDate value, long amount, String unit) {
        ChronoUnit chrono = unit(unit);
        if (chrono.getDuration().compareTo(ChronoUnit.DAYS.getDuration()) < 0) {
            throw new ExprError("cannot add " + unit + " to a DATE; cast it to TIMESTAMP first");
        }
        return isQuarter(unit) ? value.plusMonths(3 * amount) : value.plus(amount, chrono);
    }

    @Returns("$0")
    @ScalarFunction(name = "DATE_ADD")
    public static LocalDateTime dateAdd(LocalDateTime value, long amount, String unit) {
        return isQuarter(unit) ? value.plusMonths(3 * amount) : value.plus(amount, unit(unit));
    }

    @Returns("$0")
    @ScalarFunction(name = "DATE_ADD")
    public static Instant dateAdd(FunctionContext ctx, Instant value, long amount, String unit) {
        return dateAdd(local(ctx, value), amount, unit).atZone(ctx.timeZone()).toInstant();
    }

    @ScalarFunction(name = "DATE_SUB")
    public static LocalDate dateSub(LocalDate value, long days) {
        return value.minusDays(days);
    }

    @ScalarFunction(name = "DATE_SUB")
    public static LocalDate dateSub(LocalDate value, long amount, String unit) {
        return dateAdd(value, Math.negateExact(amount), unit);
    }

    @Returns("$0")
    @ScalarFunction(name = "DATE_SUB")
    public static LocalDateTime dateSub(LocalDateTime value, long amount, String unit) {
        return dateAdd(value, Math.negateExact(amount), unit);
    }

    @Returns("$0")
    @ScalarFunction(name = "DATE_SUB")
    public static Instant dateSub(FunctionContext ctx, Instant value, long amount, String unit) {
        return dateAdd(ctx, value, Math.negateExact(amount), unit);
    }

    /** Days from {@code start} to {@code end} (calendar dates). */
    @ScalarFunction(name = "DATEDIFF")
    public static long dateDiff(LocalDateTime end, LocalDateTime start) {
        return ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
    }

    /** Whole {@code unit}s from {@code start} to {@code end}. */
    @ScalarFunction(name = "TIMESTAMPDIFF")
    public static long timestampDiff(String unit, LocalDateTime start, LocalDateTime end) {
        if (isQuarter(unit)) {
            return ChronoUnit.MONTHS.between(start, end) / 3;
        }
        return unit(unit).between(start, end);
    }

    @ScalarFunction(name = "TIMESTAMPDIFF")
    public static long timestampDiff(FunctionContext ctx, String unit, Instant start, Instant end) {
        return timestampDiff(unit, local(ctx, start), local(ctx, end));
    }

    @ScalarFunction(name = "DATE_TRUNC")
    public static LocalDate dateTrunc(String unit, LocalDate value) {
        return dateTrunc(unit, value.atStartOfDay()).toLocalDate();
    }

    /** Truncates to the start of the year, quarter, month, week (Monday), day, hour, minute or second. */
    @Returns("$1")
    @ScalarFunction(name = "DATE_TRUNC")
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

    @Returns("$1")
    @ScalarFunction(name = "DATE_TRUNC")
    public static Instant dateTrunc(FunctionContext ctx, String unit, Instant value) {
        return dateTrunc(unit, local(ctx, value)).atZone(ctx.timeZone()).toInstant();
    }

    @ScalarFunction(name = "LAST_DAY")
    public static LocalDate lastDay(LocalDateTime value) {
        return value.toLocalDate().with(TemporalAdjusters.lastDayOfMonth());
    }

    /** Converts a local time from one time zone to another. */
    @Returns("$0")
    @ScalarFunction(name = "CONVERT_TZ")
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
