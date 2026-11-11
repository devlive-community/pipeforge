/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public static method as an overload of a scalar function. Parameter and result types
 * follow from the Java types ({@code String} → STRING, {@code long} → INT64, {@code BigDecimal}
 * → any DECIMAL, {@code LocalDateTime} → any TIMESTAMP, ...). A first parameter of type {@code
 * EvaluationContext} receives the job context and is not a function argument.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Scalar {

    /** Function name; upper case, with a namespace for UDFs ({@code ACME.MASK_CARD}). */
    String name();

    /**
     * Result type: empty to derive it from the Java return type, a type such as {@code
     * TIMESTAMP(3)}, {@code $n} for the type of argument {@code n}, or {@code $n:integral} for a
     * DECIMAL argument without fractional digits (CEIL, FLOOR).
     */
    String returns() default "";

    /** Whether equal arguments always give equal results. */
    boolean deterministic() default true;

    /** Whether a NULL argument makes the result NULL without calling the method. */
    boolean nullOnNull() default true;
}
