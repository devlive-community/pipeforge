/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin.check;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.devlive.pipeforge.common.plugin.PluginType;
import org.jspecify.annotations.Nullable;

/**
 * Loads the components a plugin declares and checks them: every class exists, implements the
 * factory interface of its type, can be instantiated, and reports the identifier declared in
 * plugin.yaml. Capabilities are read from the component type that the factory method returns.
 */
public final class PluginInspector {

    /** Package of the optional capability interfaces. */
    static final String CAPABILITY_PACKAGE = "org.devlive.pipeforge.api.capability";

    /** A component that passed inspection. */
    public record Inspected(
            PluginDescriptor.Component component, @Nullable OptionRule rule, List<String> capabilities) {
        public Inspected {
            capabilities = List.copyOf(capabilities);
        }
    }

    /** Components that could be inspected, and the problems found. */
    public record Result(List<Inspected> components, List<String> problems) {
        public Result {
            components = List.copyOf(components);
            problems = List.copyOf(problems);
        }
    }

    private PluginInspector() {}

    public static Result inspect(PluginDescriptor descriptor, ClassLoader loader) {
        List<Inspected> inspected = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (PluginDescriptor.Component component : descriptor.provides()) {
            Inspected result = inspect(component, loader, problems);
            if (result != null) {
                inspected.add(result);
            }
        }
        return new Result(inspected, problems);
    }

    private static @Nullable Inspected inspect(
            PluginDescriptor.Component component, ClassLoader loader, List<String> problems) {
        String where = component.type().id() + " '" + component.identifier() + "'";
        Class<?> type;
        try {
            type = Class.forName(component.className(), false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            problems.add(where + ": class " + component.className() + " not found");
            return null;
        }
        if (component.type().factoryInterface().isEmpty()) {
            return new Inspected(component, null, List.of());
        }
        Class<?> expected;
        try {
            expected = Class.forName(component.type().factoryInterface().get(), false, loader);
        } catch (ClassNotFoundException e) {
            problems.add(where + ": the API does not define "
                    + component.type().factoryInterface().get());
            return null;
        }
        if (!expected.isAssignableFrom(type)) {
            problems.add(where + ": " + type.getName() + " does not implement " + expected.getSimpleName());
            return null;
        }
        if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())) {
            problems.add(where + ": " + type.getName() + " must be a public, concrete class");
            return null;
        }
        Factory factory;
        try {
            factory = (Factory) type.getConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            problems.add(where + ": cannot create " + type.getName() + " with its public no-argument constructor: "
                    + rootMessage(e));
            return null;
        }
        String identifier;
        OptionRule rule;
        try {
            identifier = factory.identifier();
            rule = factory.optionRule();
        } catch (RuntimeException e) {
            problems.add(where + ": " + type.getName() + " fails to describe itself: " + rootMessage(e));
            return null;
        }
        if (!component.identifier().equals(identifier)) {
            problems.add(where + ": " + type.getName() + ".identifier() returns '" + identifier
                    + "' but plugin.yaml declares '" + component.identifier() + "'");
        }
        return new Inspected(component, rule, capabilities(type, component.type()));
    }

    /** Capability interfaces implemented by the type that the factory's create method returns. */
    static List<String> capabilities(Class<?> factoryType, PluginType type) {
        String methodName =
                switch (type) {
                    case SOURCE -> "createSource";
                    case SINK -> "createSink";
                    case TRANSFORM -> "createTransform";
                    case CATALOG -> "createCatalog";
                    default -> null;
                };
        if (methodName == null) {
            return List.of();
        }
        try {
            Method method = factoryType.getMethod(methodName, FactoryContext.class);
            Set<String> found = new LinkedHashSet<>();
            collect(method.getReturnType(), found);
            return List.copyOf(found);
        } catch (NoSuchMethodException e) {
            return List.of();
        }
    }

    private static void collect(@Nullable Class<?> type, Set<String> found) {
        if (type == null) {
            return;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (iface.getPackageName().equals(CAPABILITY_PACKAGE)) {
                found.add(iface.getSimpleName());
            }
            collect(iface, found);
        }
        collect(type.getSuperclass(), found);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
