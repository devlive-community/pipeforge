/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function.builtin;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.devlive.pipeforge.api.function.Returns;
import org.devlive.pipeforge.api.function.ScalarFunction;
import org.devlive.pipeforge.expressions.runtime.ExprError;

/** Numeric functions. Results that do not fit their type are errors, never wrapped values. */
public final class NumericFunctions {

    private NumericFunctions() {}

    // ------------------------------------------------------------------ ABS, SIGN

    @ScalarFunction(name = "ABS")
    public static int abs(int value) {
        if (value == Integer.MIN_VALUE) {
            throw new ExprError("numeric overflow: ABS(" + value + ") does not fit into INT32");
        }
        return Math.abs(value);
    }

    @ScalarFunction(name = "ABS")
    public static long abs(long value) {
        if (value == Long.MIN_VALUE) {
            throw new ExprError("numeric overflow: ABS(" + value + ") does not fit into INT64");
        }
        return Math.abs(value);
    }

    @Returns("$0")
    @ScalarFunction(name = "ABS")
    public static BigDecimal abs(BigDecimal value) {
        return value.abs();
    }

    @ScalarFunction(name = "ABS")
    public static double abs(double value) {
        return Math.abs(value);
    }

    @ScalarFunction(name = "SIGN")
    public static int sign(long value) {
        return Long.signum(value);
    }

    @ScalarFunction(name = "SIGN")
    public static int sign(BigDecimal value) {
        return value.signum();
    }

    @ScalarFunction(name = "SIGN")
    public static int sign(double value) {
        return (int) Math.signum(value);
    }

    // ------------------------------------------------------------------ rounding

    @Returns("$0")
    @ScalarFunction(name = "CEIL")
    public static long ceil(long value) {
        return value;
    }

    @Returns("$0:integral")
    @ScalarFunction(name = "CEIL")
    public static BigDecimal ceil(BigDecimal value) {
        return value.setScale(0, RoundingMode.CEILING);
    }

    @ScalarFunction(name = "CEIL")
    public static double ceil(double value) {
        return Math.ceil(value);
    }

    @Returns("$0")
    @ScalarFunction(name = "FLOOR")
    public static long floor(long value) {
        return value;
    }

    @Returns("$0:integral")
    @ScalarFunction(name = "FLOOR")
    public static BigDecimal floor(BigDecimal value) {
        return value.setScale(0, RoundingMode.FLOOR);
    }

    @ScalarFunction(name = "FLOOR")
    public static double floor(double value) {
        return Math.floor(value);
    }

    @Returns("$0")
    @ScalarFunction(name = "ROUND")
    public static long round(long value) {
        return value;
    }

    /** Rounds half up to {@code digits} digits; negative digits round to tens, hundreds, ... */
    @Returns("$0")
    @ScalarFunction(name = "ROUND")
    public static long round(long value, int digits) {
        if (digits >= 0) {
            return value;
        }
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP);
        try {
            return rounded.longValueExact();
        } catch (ArithmeticException e) {
            throw new ExprError("numeric overflow: ROUND(" + value + ", " + digits + ") does not fit into INT64");
        }
    }

    @Returns("$0")
    @ScalarFunction(name = "ROUND")
    public static BigDecimal round(BigDecimal value) {
        return round(value, 0);
    }

    /** Rounds half up; the result keeps the scale of its type, e.g. ROUND(1.2345, 2) = 1.2300. */
    @Returns("$0")
    @ScalarFunction(name = "ROUND")
    public static BigDecimal round(BigDecimal value, int digits) {
        return value.setScale(digits, RoundingMode.HALF_UP).setScale(value.scale(), RoundingMode.UNNECESSARY);
    }

    @ScalarFunction(name = "ROUND")
    public static double round(double value) {
        return round(value, 0);
    }

    @ScalarFunction(name = "ROUND")
    public static double round(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    @Returns("$0")
    @ScalarFunction(name = "TRUNCATE")
    public static BigDecimal truncate(BigDecimal value, int digits) {
        return value.setScale(digits, RoundingMode.DOWN).setScale(value.scale(), RoundingMode.UNNECESSARY);
    }

    @ScalarFunction(name = "TRUNCATE")
    public static double truncate(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.DOWN).doubleValue();
    }

    // ------------------------------------------------------------------ division

    /** Integer division truncating toward zero. */
    @ScalarFunction(name = "DIV")
    public static long div(long dividend, long divisor) {
        if (divisor == 0) {
            throw new ExprError("division by zero");
        }
        if (dividend == Long.MIN_VALUE && divisor == -1) {
            throw new ExprError("numeric overflow: the result does not fit into INT64");
        }
        return dividend / divisor;
    }

    @ScalarFunction(name = "DIV")
    public static long div(BigDecimal dividend, BigDecimal divisor) {
        if (divisor.signum() == 0) {
            throw new ExprError("division by zero");
        }
        try {
            return dividend.divideToIntegralValue(divisor).longValueExact();
        } catch (ArithmeticException e) {
            throw new ExprError("numeric overflow: the result does not fit into INT64");
        }
    }

    // ------------------------------------------------------------------ powers and logarithms

    @ScalarFunction(name = "POWER")
    public static double power(double base, double exponent) {
        return finite(Math.pow(base, exponent), "POWER");
    }

    @ScalarFunction(name = "SQRT")
    public static double sqrt(double value) {
        if (value < 0) {
            throw new ExprError("SQRT of a negative number: " + value);
        }
        return Math.sqrt(value);
    }

    @ScalarFunction(name = "EXP")
    public static double exp(double value) {
        return finite(Math.exp(value), "EXP");
    }

    @ScalarFunction(name = "LN")
    public static double ln(double value) {
        return Math.log(positive(value, "LN"));
    }

    /** Natural logarithm with one argument; LOG(base, x) with two. */
    @ScalarFunction(name = "LOG")
    public static double log(double value) {
        return ln(value);
    }

    @ScalarFunction(name = "LOG")
    public static double log(double base, double value) {
        positive(base, "LOG");
        if (base == 1) {
            throw new ExprError("LOG with base 1 is undefined");
        }
        return Math.log(positive(value, "LOG")) / Math.log(base);
    }

    @ScalarFunction(name = "LOG10")
    public static double log10(double value) {
        return Math.log10(positive(value, "LOG10"));
    }

    private static double positive(double value, String function) {
        if (!(value > 0)) {
            throw new ExprError(function + " needs a positive number, got " + value);
        }
        return value;
    }

    private static double finite(double value, String function) {
        if (Double.isInfinite(value)) {
            throw new ExprError("numeric overflow in " + function);
        }
        if (Double.isNaN(value)) {
            throw new ExprError(function + " is undefined for these arguments");
        }
        return value;
    }

    // ------------------------------------------------------------------ random

    /** A random number in [0, 1); evaluated anew for every row. */
    @ScalarFunction(name = "RAND", deterministic = false)
    public static double rand() {
        return ThreadLocalRandom.current().nextDouble();
    }

    // ------------------------------------------------------------------ bases

    @ScalarFunction(name = "BIN")
    public static String bin(long value) {
        return Long.toBinaryString(value);
    }

    @ScalarFunction(name = "HEX")
    public static String hex(long value) {
        return Long.toHexString(value).toUpperCase(Locale.ROOT);
    }

    /** Hexadecimal digits of the UTF-8 bytes of a string. */
    @ScalarFunction(name = "HEX")
    public static String hex(String value) {
        return hex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @ScalarFunction(name = "HEX")
    public static String hex(byte[] value) {
        return java.util.HexFormat.of().withUpperCase().formatHex(value);
    }

    /** Converts a number written in base {@code from} to base {@code to} (bases 2 to 36). */
    @ScalarFunction(name = "CONV")
    public static String conv(String number, int from, int to) {
        checkBase(from);
        checkBase(to);
        try {
            return new BigInteger(number.trim(), from).toString(to).toUpperCase(Locale.ROOT);
        } catch (NumberFormatException e) {
            throw new ExprError("'" + number + "' is not a number in base " + from);
        }
    }

    private static void checkBase(int base) {
        if (base < Character.MIN_RADIX || base > Character.MAX_RADIX) {
            throw new ExprError("bases must be between 2 and 36, got " + base);
        }
    }
}
