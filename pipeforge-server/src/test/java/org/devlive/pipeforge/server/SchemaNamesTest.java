/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The OpenAPI document names a schema after the simple name of its Java type, and keeps one of two
 * types with the same name; the other one's fields vanish from the document and the generated
 * clients. Every record the API takes or returns must have a name of its own.
 */
@SpringBootTest
class SchemaNamesTest {

    @Autowired
    RequestMappingHandlerMapping mapping;

    @Test
    void everyTypeOfTheApiHasItsOwnSchemaName() {
        Deque<Type> pending = new ArrayDeque<>();
        for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
            if (!handler.getBeanType().getPackageName().startsWith("org.devlive.pipeforge")) {
                continue;
            }
            Method method = handler.getMethod();
            pending.add(method.getGenericReturnType());
            pending.addAll(java.util.List.of(method.getGenericParameterTypes()));
        }
        Set<Class<?>> seen = new HashSet<>();
        Map<String, Set<String>> names = new TreeMap<>();
        while (!pending.isEmpty()) {
            Type type = pending.pop();
            if (type instanceof ParameterizedType parameterized) {
                pending.addAll(java.util.List.of(parameterized.getActualTypeArguments()));
                type = parameterized.getRawType();
            }
            if (!(type instanceof Class<?> found) || !seen.add(found)) {
                continue;
            }
            if (found.isArray()) {
                pending.add(found.getComponentType());
                continue;
            }
            if (!found.isRecord()) {
                continue;
            }
            names.computeIfAbsent(found.getSimpleName(), k -> new TreeSet<>()).add(found.getName());
            for (RecordComponent component : found.getRecordComponents()) {
                pending.add(component.getGenericType());
            }
        }
        assertThat(names).as("records of the API").isNotEmpty().containsKey("RunMetrics");
        assertThat(names.values())
                .as("records of the API that share a schema name")
                .allSatisfy(types -> assertThat(types).hasSize(1));
    }
}
