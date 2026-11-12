/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.function.FunctionContext;
import org.devlive.pipeforge.api.function.Param;
import org.devlive.pipeforge.api.function.Returns;
import org.devlive.pipeforge.api.function.ScalarFunction;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.expressions.function.FunctionDefinition.ReturnType;
import org.devlive.pipeforge.expressions.function.FunctionDefinition.Signature;

/**
 * Builds {@link FunctionDefinition}s from classes whose public methods carry {@link ScalarFunction},
 * with {@link Returns} and {@link Param} for logical types the Java types cannot express.
 */
public final class ScalarFunctions {

    private ScalarFunctions() {}

    /** Registers every {@link ScalarFunction} method of the provider's classes, grouped into functions by name. */
    public static FunctionRegistry register(FunctionRegistry registry, FunctionProvider provider) {
        Map<String, List<Method>> byName = new LinkedHashMap<>();
        for (Class<?> type : provider.classes()) {
            Method[] methods = type.getMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparing(Method::toGenericString));
            for (Method method : methods) {
                ScalarFunction scalar = method.getAnnotation(ScalarFunction.class);
                if (scalar != null) {
                    byName.computeIfAbsent(provider.qualify(scalar.name()), k -> new ArrayList<>())
                            .add(method);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(ScalarFunction.class) && !Modifier.isPublic(method.getModifiers())) {
                    throw new IllegalArgumentException(method + " carries @ScalarFunction but is not public");
                }
            }
        }
        for (Map.Entry<String, List<Method>> entry : byName.entrySet()) {
            registry.register(define(entry.getKey(), entry.getValue()));
        }
        return registry;
    }

    static FunctionDefinition define(String name, List<Method> methods) {
        ScalarFunction first = methods.get(0).getAnnotation(ScalarFunction.class);
        List<Signature> signatures = new ArrayList<>();
        boolean deterministic = true;
        for (Method method : methods) {
            ScalarFunction scalar = method.getAnnotation(ScalarFunction.class);
            deterministic &= scalar.deterministic();
            if (scalar.nullOnNull() != first.nullOnNull()) {
                throw new IllegalArgumentException("All overloads of " + name + " must agree on nullOnNull");
            }
            signatures.add(signature(method, scalar));
        }
        signatures.sort(Comparator.comparingInt(ScalarFunctions::rank));
        return new FunctionDefinition(name, signatures, deterministic, first.nullOnNull());
    }

    /** Narrower parameter types first, so that INT32 arguments pick an INT32 overload over INT64. */
    private static int rank(Signature signature) {
        int rank = signature.parameters().size() * 100;
        for (DataType type : signature.parameters()) {
            rank += switch (type.root()) {
                case INT8 -> 1;
                case INT16 -> 2;
                case INT32 -> 3;
                case INT64 -> 4;
                case DECIMAL -> 5;
                case FLOAT32 -> 6;
                case FLOAT64 -> 7;
                case DATE -> 1;
                case TIMESTAMP -> 2;
                case TIMESTAMP_LTZ -> 3;
                default -> 0;
            };
        }
        return rank;
    }

    /** Whether a function implementation takes the context as first parameter. */
    public static boolean takesContext(Method method) {
        return method.getParameterCount() > 0 && method.getParameterTypes()[0] == FunctionContext.class;
    }

    private static Signature signature(Method method, ScalarFunction scalar) {
        Class<?>[] params = method.getParameterTypes();
        Parameter[] declared = method.getParameters();
        int offset = takesContext(method) ? 1 : 0;
        List<DataType> parameters = new ArrayList<>();
        List<Boolean> exact = new ArrayList<>();
        for (int i = offset; i < params.length; i++) {
            Class<?> type = method.isVarArgs() && i == params.length - 1 ? params[i].getComponentType() : params[i];
            if (!scalar.nullOnNull() && type.isPrimitive()) {
                throw new IllegalArgumentException(
                        method + " receives NULL arguments (nullOnNull = false), so parameter " + (i - offset)
                                + " needs a reference type instead of " + type.getName());
            }
            Param param = declared[i].getAnnotation(Param.class);
            parameters.add(param != null ? parse(param.value(), method) : logical(type, method));
            exact.add(param != null);
        }
        Returns returns = method.getAnnotation(Returns.class);
        ReturnType result = returnType(returns == null ? "" : returns.value(), method, parameters.size());
        return new Signature(parameters, method.isVarArgs(), result, method, exact);
    }

    private static ReturnType returnType(String spec, Method method, int arity) {
        if (spec.isEmpty()) {
            return ReturnType.fixed(logical(method.getReturnType(), method));
        }
        if (spec.startsWith("$")) {
            String[] parts = spec.substring(1).split(":", 2);
            int index;
            try {
                index = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid @Returns(\"" + spec + "\") on " + method, e);
            }
            if (index < 0 || index >= arity || (parts.length > 1 && !parts[1].equals("integral"))) {
                throw new IllegalArgumentException("Invalid @Returns(\"" + spec + "\") on " + method
                        + ": use $n with n below " + arity + ", optionally followed by :integral");
            }
            boolean integral = parts.length > 1;
            return arguments -> {
                DataType type = arguments.get(index).nullable();
                if (integral && type.root() == TypeRoot.DECIMAL) {
                    return DataTypes.DECIMAL(
                            Math.min(DataTypes.MAX_DECIMAL_PRECISION, type.precision() - type.scale() + 1), 0);
                }
                return type;
            };
        }
        return ReturnType.fixed(parse(spec, method));
    }

    private static DataType parse(String type, Method method) {
        try {
            return DataTypes.parse(type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid logical type '" + type + "' on " + method, e);
        }
    }

    /** The logical type that a Java parameter or result type stands for. */
    static DataType logical(Class<?> type, Method method) {
        if (type == String.class) {
            return DataTypes.STRING();
        }
        if (type == int.class || type == Integer.class) {
            return DataTypes.INT32();
        }
        if (type == long.class || type == Long.class) {
            return DataTypes.INT64();
        }
        if (type == double.class || type == Double.class) {
            return DataTypes.FLOAT64();
        }
        if (type == boolean.class || type == Boolean.class) {
            return DataTypes.BOOLEAN();
        }
        if (type == BigDecimal.class) {
            return DataTypes.DECIMAL(DataTypes.MAX_DECIMAL_PRECISION, 18);
        }
        if (type == LocalDate.class) {
            return DataTypes.DATE();
        }
        if (type == LocalDateTime.class) {
            return DataTypes.TIMESTAMP();
        }
        if (type == Instant.class) {
            return DataTypes.TIMESTAMP_LTZ();
        }
        if (type == LocalTime.class) {
            return DataTypes.TIME();
        }
        if (type == byte[].class) {
            return DataTypes.BYTES();
        }
        if (type == List.class) {
            return DataTypes.ARRAY(DataTypes.STRING());
        }
        throw new IllegalArgumentException("No logical type for " + type.getName() + " in " + method);
    }
}
