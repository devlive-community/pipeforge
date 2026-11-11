/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * The JUnit 5 extension of the testkit: resolves {@link TestkitEngine} parameters with one engine
 * per test run, closed when the run ends.
 *
 * <pre>{@code
 * @ExtendWith(TestkitExtension.class)
 * class MySinkTest {
 *     @Test
 *     void writes(TestkitEngine engine) { ... }
 * }
 * }</pre>
 *
 * <p>The contract base classes ({@link SourceContract}, {@link SinkContract}) register it already.
 */
public final class TestkitExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TestkitExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == TestkitEngine.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return context.getRoot()
                .getStore(NAMESPACE)
                .computeIfAbsent(TestkitEngine.class, type -> new TestkitEngine(), TestkitEngine.class);
    }
}
