/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.expressions.function.builtin.BuiltinFunctions;
import org.junit.jupiter.api.Test;

/**
 * The built-in registry, entry by entry: names, overloads, parameter and result types, determinism,
 * NULL behavior and implementing methods. The snapshot pins the library, so a change to how
 * functions are declared cannot change what they are; update it with {@code
 * -Dpipeforge.snapshot.update=true} when a function is added or changed on purpose.
 */
class RegistrySnapshotTest {

    static final String RESOURCE = "functions/registry-snapshot.txt";

    static String render(FunctionRegistry registry) {
        StringBuilder text = new StringBuilder();
        for (FunctionDefinition function : registry.all()) {
            text.append(function.name())
                    .append(function.deterministic() ? "" : " nondeterministic")
                    .append(function.nullOnNull() ? "" : " null-tolerant")
                    .append('\n');
            for (FunctionDefinition.Signature signature : function.signatures()) {
                List<String> parameters = new ArrayList<>();
                for (DataType type : signature.parameters()) {
                    parameters.add(type.toString());
                }
                text.append("  (")
                        .append(String.join(", ", parameters))
                        .append(signature.variadic() ? "..." : "")
                        .append(") -> ")
                        .append(signature.returns().infer(signature.parameters()))
                        .append("  ")
                        .append(method(signature.method()))
                        .append('\n');
            }
        }
        return text.toString();
    }

    /** The implementation, with the context parameter named by role so that its Java type may change. */
    private static String method(Method method) {
        List<String> types = new ArrayList<>();
        for (Class<?> type : method.getParameterTypes()) {
            types.add(type.getSimpleName().endsWith("Context") ? "context" : type.getSimpleName());
        }
        return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "(" + String.join(", ", types)
                + ") " + method.getReturnType().getSimpleName();
    }

    @Test
    void theBuiltInRegistryMatchesTheSnapshot() throws Exception {
        String actual = render(BuiltinFunctions.registry());
        if (Boolean.getBoolean("pipeforge.snapshot.update")) {
            Files.writeString(Path.of("src/test/resources").resolve(RESOURCE), actual, StandardCharsets.UTF_8);
        }
        String expected;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(expected);
    }
}
