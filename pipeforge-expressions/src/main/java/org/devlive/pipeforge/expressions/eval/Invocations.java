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
import org.devlive.pipeforge.expressions.function.FunctionCall;
import org.devlive.pipeforge.expressions.runtime.ExprError;

/** Calls function implementations reflectively (used by the interpreter). */
final class Invocations {

    private Invocations() {}

    static Object invoke(FunctionCall call, Object[] args) {
        if (call.definition().nullOnNull()) {
            for (Object arg : args) {
                if (arg == null) {
                    return null;
                }
            }
        }
        Method method = call.signature().method();
        Object[] actual = pack(method, args);
        try {
            return method.invoke(null, actual);
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
    static Object[] pack(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();
        if (!method.isVarArgs()) {
            return args;
        }
        int fixed = types.length - 1;
        Object[] actual = new Object[types.length];
        System.arraycopy(args, 0, actual, 0, fixed);
        Class<?> component = types[fixed].getComponentType();
        Object rest = Array.newInstance(component, args.length - fixed);
        for (int i = fixed; i < args.length; i++) {
            Array.set(rest, i - fixed, args[i]);
        }
        actual[fixed] = rest;
        return actual;
    }
}
