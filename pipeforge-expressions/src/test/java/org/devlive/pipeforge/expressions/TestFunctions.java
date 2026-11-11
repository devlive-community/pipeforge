/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.expressions.function.FunctionDefinition;
import org.devlive.pipeforge.expressions.function.FunctionDefinition.ReturnType;
import org.devlive.pipeforge.expressions.function.FunctionDefinition.Signature;
import org.devlive.pipeforge.expressions.function.FunctionRegistry;
import org.devlive.pipeforge.expressions.runtime.ExprError;

/** Functions used to observe how often and how expressions call functions. */
public final class TestFunctions {

    public static final AtomicInteger TWICE_CALLS = new AtomicInteger();
    public static final AtomicInteger NOISE_CALLS = new AtomicInteger();

    private TestFunctions() {}

    public static long twice(long value) {
        TWICE_CALLS.incrementAndGet();
        return value * 2;
    }

    public static double noise(long seed) {
        return seed + NOISE_CALLS.incrementAndGet();
    }

    public static String join(String separator, String... parts) {
        return String.join(separator, parts);
    }

    public static String describe(Object value) {
        return value == null ? "nothing" : "some " + value;
    }

    public static int strict(int value) {
        if (value < 0) {
            throw new ExprError("negative values are not allowed");
        }
        return value;
    }

    public static boolean fails(String value) {
        throw new IllegalStateException("broken for " + value);
    }

    static Signature signature(String method, List<DataType> parameters, boolean variadic, DataType returns) {
        for (var m : TestFunctions.class.getMethods()) {
            if (m.getName().equals(method)) {
                return new Signature(parameters, variadic, ReturnType.fixed(returns), m);
            }
        }
        throw new IllegalArgumentException(method);
    }

    static FunctionRegistry registry() {
        return FunctionRegistry.empty()
                .register(new FunctionDefinition(
                        "TWICE",
                        List.of(signature("twice", List.of(DataTypes.INT64()), false, DataTypes.INT64())),
                        true,
                        true))
                .register(new FunctionDefinition(
                        "NOISE",
                        List.of(signature("noise", List.of(DataTypes.INT64()), false, DataTypes.FLOAT64())),
                        false,
                        true))
                .register(new FunctionDefinition(
                        "TEST.GLUE",
                        List.of(signature(
                                "join", List.of(DataTypes.STRING(), DataTypes.STRING()), true, DataTypes.STRING())),
                        true,
                        true))
                .register(new FunctionDefinition(
                        "SHOW_VALUE",
                        List.of(signature(
                                "describe",
                                List.of(DataTypes.STRING()),
                                false,
                                DataTypes.STRING().notNull())),
                        true,
                        false))
                .register(new FunctionDefinition(
                        "STRICT",
                        List.of(signature("strict", List.of(DataTypes.INT32()), false, DataTypes.INT32())),
                        true,
                        true))
                .register(new FunctionDefinition(
                        "FAILS",
                        List.of(signature("fails", List.of(DataTypes.STRING()), false, DataTypes.BOOLEAN())),
                        true,
                        true));
    }
}
