/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.expressions.EvaluationContext;
import org.devlive.pipeforge.expressions.function.FunctionDefinition.ReturnType;
import org.devlive.pipeforge.expressions.function.FunctionDefinition.Signature;

/** Builds {@link FunctionDefinition}s from classes whose static methods carry {@link Scalar}. */
public final class ScalarFunctions {

    private ScalarFunctions() {}

    /** Registers every {@link Scalar} method of the classes, grouped into functions by name. */
    public static FunctionRegistry register(FunctionRegistry registry, Class<?>... classes) {
        Map<String, List<Method>> byName = new LinkedHashMap<>();
        for (Class<?> type : classes) {
            Method[] methods = type.getMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparing(Method::toGenericString));
            for (Method method : methods) {
                Scalar scalar = method.getAnnotation(Scalar.class);
                if (scalar != null) {
                    byName.computeIfAbsent(scalar.name(), k -> new ArrayList<>())
                            .add(method);
                }
            }
        }
        for (Map.Entry<String, List<Method>> entry : byName.entrySet()) {
            registry.register(define(entry.getKey(), entry.getValue()));
        }
        return registry;
    }

    static FunctionDefinition define(String name, List<Method> methods) {
        List<Signature> signatures = new ArrayList<>();
        boolean deterministic = true;
        boolean nullOnNull = true;
        for (Method method : methods) {
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException(method + " must be static");
            }
            Scalar scalar = method.getAnnotation(Scalar.class);
            deterministic &= scalar.deterministic();
            nullOnNull &= scalar.nullOnNull();
            signatures.add(signature(method, scalar));
        }
        if (methods.stream()
                .map(m -> m.getAnnotation(Scalar.class))
                .anyMatch(s -> s.nullOnNull()
                        != methods.get(0).getAnnotation(Scalar.class).nullOnNull())) {
            throw new IllegalArgumentException("All overloads of " + name + " must agree on nullOnNull");
        }
        signatures.sort(Comparator.comparingInt(ScalarFunctions::rank));
        return new FunctionDefinition(name, signatures, deterministic, nullOnNull);
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

    private static Signature signature(Method method, Scalar scalar) {
        Class<?>[] params = method.getParameterTypes();
        int offset = params.length > 0 && params[0] == EvaluationContext.class ? 1 : 0;
        List<DataType> parameters = new ArrayList<>();
        for (int i = offset; i < params.length; i++) {
            Class<?> type = method.isVarArgs() && i == params.length - 1 ? params[i].getComponentType() : params[i];
            parameters.add(logical(type, method));
        }
        ReturnType returns = returnType(scalar.returns(), method);
        return new Signature(parameters, method.isVarArgs(), returns, method);
    }

    private static ReturnType returnType(String spec, Method method) {
        if (spec.isEmpty()) {
            return ReturnType.fixed(logical(method.getReturnType(), method));
        }
        if (spec.startsWith("$")) {
            String[] parts = spec.substring(1).split(":", 2);
            int index = Integer.parseInt(parts[0]);
            boolean integral = parts.length > 1 && parts[1].equals("integral");
            return arguments -> {
                DataType type = arguments.get(index).nullable();
                if (integral && type.root() == TypeRoot.DECIMAL) {
                    return DataTypes.DECIMAL(
                            Math.min(DataTypes.MAX_DECIMAL_PRECISION, type.precision() - type.scale() + 1), 0);
                }
                return type;
            };
        }
        DataType fixed = DataTypes.parse(spec);
        return ReturnType.fixed(fixed);
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
