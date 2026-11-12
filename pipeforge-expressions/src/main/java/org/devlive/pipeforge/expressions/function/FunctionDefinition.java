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
import org.devlive.pipeforge.api.function.FunctionLifecycle;
import org.devlive.pipeforge.api.type.DataType;

/**
 * A scalar function: its name, how its result type follows from the argument types, whether it
 * is deterministic, how it treats NULL, and the methods that implement its overloads.
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
     * @param parameters parameter types; arguments are implicitly cast to them, except that a
     *     parameter of a parameterized family (DECIMAL, STRING, ...) that is not {@code exact}
     *     accepts any member of the family unchanged
     * @param variadic whether the last parameter repeats
     * @param returns the result type for given argument types
     * @param method the public implementation: static, or an instance method of a class that
     *     implements {@link FunctionLifecycle}
     * @param exact per parameter, whether its type was declared ({@code @Param}) and arguments are
     *     cast to exactly that type
     */
    public record Signature(
            List<DataType> parameters, boolean variadic, ReturnType returns, Method method, List<Boolean> exact) {

        /** A signature whose parameterized parameters accept any member of their family. */
        public Signature(List<DataType> parameters, boolean variadic, ReturnType returns, Method method) {
            this(parameters, variadic, returns, method, java.util.Collections.nCopies(parameters.size(), false));
        }

        public Signature {
            parameters = List.copyOf(parameters);
            exact = List.copyOf(exact);
            if (exact.size() != parameters.size()) {
                throw new IllegalArgumentException(method + ": one exact flag per parameter");
            }
            if (!Modifier.isPublic(method.getModifiers())
                    || !Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                throw new IllegalArgumentException(method + " must be public and declared in a public class");
            }
            if (!Modifier.isStatic(method.getModifiers())
                    && !FunctionLifecycle.class.isAssignableFrom(method.getDeclaringClass())) {
                throw new IllegalArgumentException(method + " must be static, or "
                        + method.getDeclaringClass().getName() + " must implement FunctionLifecycle");
            }
        }

        /** Whether parameter {@code index} (the last one repeats for variadic signatures) is exact. */
        public boolean exact(int index) {
            return exact.get(Math.min(index, exact.size() - 1));
        }

        /** Whether the implementation is an instance method, called on a per-evaluator object. */
        public boolean instance() {
            return !Modifier.isStatic(method.getModifiers());
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
