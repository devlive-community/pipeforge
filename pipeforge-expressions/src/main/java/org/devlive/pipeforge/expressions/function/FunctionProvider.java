/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A source of functions: classes whose methods carry {@link
 * org.devlive.pipeforge.api.function.ScalarFunction}. The built-in library is the provider without
 * a namespace; every function plugin is a provider whose namespace prefixes its function names
 * ({@code acme} turns {@code mask_card} into {@code ACME.MASK_CARD}).
 *
 * @param name what the provider is, for error messages ({@code builtin}, {@code acme-functions@1.0.0})
 * @param namespace the namespace of the function names, or null for the built-in library
 * @param classes the annotated classes
 */
public record FunctionProvider(String name, @Nullable String namespace, List<Class<?>> classes) {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9_]*");

    public FunctionProvider {
        classes = List.copyOf(classes);
        if (namespace != null && !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Function namespace '" + namespace + "' of " + name + " must match " + NAMESPACE);
        }
    }

    /** The registered name of a function this provider declares as {@code name}. */
    public String qualify(String name) {
        if (namespace != null && name.contains(".")) {
            throw new IllegalArgumentException("Function " + name + " of " + this.name
                    + " must be declared without a namespace; it is registered in " + namespace);
        }
        String qualified = namespace == null ? name : namespace + "." + name;
        return qualified.toUpperCase(Locale.ROOT);
    }
}
