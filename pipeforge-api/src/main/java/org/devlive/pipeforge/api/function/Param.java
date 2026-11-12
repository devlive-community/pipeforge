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
 * The logical type of a {@link ScalarFunction} parameter, for parameters whose Java type is not
 * precise enough, such as {@code DECIMAL(16, 2)} for a {@code BigDecimal} or {@code ARRAY<INT64>}
 * for a {@code List}. Arguments are implicitly cast to it.
 */
@Evolving
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Param {

    String value();
}
