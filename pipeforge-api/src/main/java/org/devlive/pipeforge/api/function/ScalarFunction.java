/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.devlive.pipeforge.api.annotation.Evolving;

/**
 * Marks a public method as an overload of a scalar function; methods with the same name are
 * overloads of one function, and one class may hold several functions.
 *
 * <p>A static method is stateless. An instance method requires its class to implement {@link
 * FunctionLifecycle}: every parallel instance of an operator then gets its own object, opened before
 * the first call and closed after the last.
 *
 * <p>Parameter and result types follow from the Java types ({@code String} → {@code STRING},
 * {@code int} → {@code INT32}, {@code long} → {@code INT64}, {@code double} → {@code FLOAT64},
 * {@code boolean} → {@code BOOLEAN}, {@code BigDecimal} → {@code DECIMAL(38, 18)}, {@code
 * LocalDate} → {@code DATE}, {@code LocalTime} → {@code TIME}, {@code LocalDateTime} → {@code
 * TIMESTAMP}, {@code Instant} → {@code TIMESTAMP_LTZ}, {@code byte[]} → {@code BYTES}); {@link
 * Returns} and {@link Param} declare a logical type where the Java type is not precise enough. A
 * first parameter of type {@link FunctionContext} receives the context and is not a function
 * argument. A variadic method repeats its last parameter.
 *
 * <pre>{@code
 * public final class CardFunctions {
 *     @ScalarFunction(name = "mask_card")
 *     public static String maskCard(String cardNo) {
 *         return "****" + cardNo.substring(Math.max(0, cardNo.length() - 4));
 *     }
 * }
 * }</pre>
 */
@Evolving
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScalarFunction {

    /**
     * The function name, matched case-insensitively. A function plugin writes it without its
     * namespace ({@code mask_card}); the function is registered as {@code acme.mask_card}.
     */
    String name();

    /**
     * Whether equal arguments always give equal results. Nondeterministic functions such as {@code
     * NOW()} are never pushed down to sources or evaluated once for equal subexpressions. A function
     * counts as deterministic only when all its overloads are, so {@code UNIX_TIMESTAMP()} makes
     * {@code UNIX_TIMESTAMP} nondeterministic.
     */
    boolean deterministic() default true;

    /**
     * Whether a NULL argument makes the result NULL without calling the method. When false, the
     * method receives NULL arguments, so its parameters must have reference types. All overloads of a
     * function must agree.
     */
    boolean nullOnNull() default true;
}
