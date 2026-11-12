/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.eval;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.devlive.pipeforge.api.function.FunctionContext;
import org.devlive.pipeforge.expressions.function.FunctionCall;
import org.devlive.pipeforge.expressions.function.ScalarFunctions;
import org.devlive.pipeforge.expressions.runtime.ExprError;
import org.jspecify.annotations.Nullable;

/** Calls function implementations reflectively (used by the interpreter). */
final class Invocations {

    private Invocations() {}

    /** Whether a function implementation takes the context as first parameter. */
    static boolean takesContext(Method method) {
        return ScalarFunctions.takesContext(method);
    }

    static Object invoke(FunctionCall call, Object[] args, FunctionContext context, FunctionInstances instances) {
        if (call.definition().nullOnNull()) {
            for (Object arg : args) {
                if (arg == null) {
                    return null;
                }
            }
        }
        Method method = call.signature().method();
        Object[] actual = pack(method, args, takesContext(method) ? context : null);
        try {
            Object target = call.signature().instance() ? instances.get(method.getDeclaringClass()) : null;
            return method.invoke(target, actual);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ExprError(call.definition().name() + " failed: " + cause);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new ExprError(call.definition().name() + " cannot be called with these arguments: " + e.getMessage());
        }
    }

    /** Packs trailing arguments into the array parameter of a varargs method. */
    static Object[] pack(Method method, Object[] args, @Nullable FunctionContext context) {
        Class<?>[] types = method.getParameterTypes();
        int offset = context == null ? 0 : 1;
        Object[] actual = new Object[types.length];
        if (offset == 1) {
            actual[0] = context;
        }
        if (!method.isVarArgs()) {
            System.arraycopy(args, 0, actual, offset, args.length);
            return actual;
        }
        int fixed = types.length - 1 - offset;
        System.arraycopy(args, 0, actual, offset, fixed);
        Class<?> component = types[types.length - 1].getComponentType();
        Object rest = Array.newInstance(component, args.length - fixed);
        for (int i = fixed; i < args.length; i++) {
            Array.set(rest, i - fixed, args[i]);
        }
        actual[types.length - 1] = rest;
        return actual;
    }
}
