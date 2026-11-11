/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function.builtin;

import java.util.List;
import org.devlive.pipeforge.expressions.function.FunctionRegistry;
import org.devlive.pipeforge.expressions.function.ScalarFunctions;

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

    /** A new registry with every built-in function; function plugins may add to it. */
    public static FunctionRegistry registry() {
        return ScalarFunctions.register(
                FunctionRegistry.empty(),
                ComparisonFunctions.class,
                NumericFunctions.class,
                StringFunctions.class,
                DateTimeFunctions.class);
    }
}
