/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import org.devlive.pipeforge.api.type.DataType;

/**
 * A scalar function: its name, how its result type follows from the argument types, whether it
 * is deterministic, how it treats NULL, and the static method that implements it.
 *
 * <p>The implementation takes the argument values as declared Java types (after the analyzer
 * casts the arguments to {@link Signature#parameters()}) and returns the value of the result type.
 *
 * @param name upper-case name, with a namespace for UDFs ({@code ACME.MASK_CARD})
 * @param signatures overloads, tried in order
 * @param deterministic whether equal arguments always give equal results
 * @param nullOnNull whether a NULL argument makes the result NULL without calling the method
 */
public record FunctionDefinition(String name, List<Signature> signatures, boolean deterministic, boolean nullOnNull) {

    /**
     * One overload.
     *
     * @param parameters parameter types; arguments are implicitly cast to them
     * @param variadic whether the last parameter repeats
     * @param returns the result type for given argument types
     * @param method the public static implementation
     */
    public record Signature(List<DataType> parameters, boolean variadic, ReturnType returns, Method method) {
        public Signature {
            parameters = List.copyOf(parameters);
            if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())) {
                throw new IllegalArgumentException(method + " must be public and static");
            }
        }
    }

    /** Computes the result type from the (cast) argument types. */
    @FunctionalInterface
    public interface ReturnType {
        DataType infer(List<DataType> arguments);

        static ReturnType fixed(DataType type) {
            return arguments -> type;
        }
    }

    public FunctionDefinition {
        Objects.requireNonNull(name, "name");
        signatures = List.copyOf(signatures);
        if (signatures.isEmpty()) {
            throw new IllegalArgumentException(name + " needs at least one signature");
        }
    }
}
