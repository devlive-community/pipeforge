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
 * The logical result type of a {@link ScalarFunction} overload, for results whose Java type is not
 * precise enough.
 *
 * <ul>
 *   <li>a type, such as {@code DECIMAL(18, 2)}, {@code TIMESTAMP(3)} or {@code ARRAY<STRING>};
 *   <li>{@code $n}: the type of argument {@code n} (from 0, not counting a {@link FunctionContext});
 *   <li>{@code $n:integral}: for a {@code DECIMAL} argument {@code n}, the same type without
 *       fractional digits ({@code CEIL}, {@code FLOOR}); any other type unchanged.
 * </ul>
 */
@Evolving
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Returns {

    String value();
}
