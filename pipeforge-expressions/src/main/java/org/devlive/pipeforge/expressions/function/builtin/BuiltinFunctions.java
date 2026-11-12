/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function.builtin;

import java.util.List;
import org.devlive.pipeforge.expressions.function.FunctionProvider;
import org.devlive.pipeforge.expressions.function.FunctionRegistry;

/** The built-in function library. */
public final class BuiltinFunctions {

    /**
     * Functions that are part of the expression syntax rather than registered implementations:
     * they evaluate lazily, take arguments of any type, or need the argument types.
     */
    public static final List<String> SYNTAX = List.of(
            "CAST",
            "TRY_CAST",
            "COALESCE",
            "IFNULL",
            "NULLIF",
            "IF",
            "GREATEST",
            "LEAST",
            "TYPEOF",
            "FORMAT",
            "MOD",
            "EXTRACT",
            "DAY");

    private BuiltinFunctions() {}

    /** The built-in library as a function provider, without a namespace. */
    public static final FunctionProvider PROVIDER = new FunctionProvider(
            "builtin",
            null,
            List.of(ComparisonFunctions.class, NumericFunctions.class, StringFunctions.class, DateTimeFunctions.class));

    /** A new registry with every built-in function. */
    public static FunctionRegistry registry() {
        return FunctionRegistry.of(List.of(PROVIDER));
    }
}
