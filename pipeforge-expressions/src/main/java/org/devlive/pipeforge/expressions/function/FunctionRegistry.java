/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The functions expressions can call, by upper-case name. The built-in library and installed
 * function plugins register here; planning, the UI and the documentation read it.
 */
public final class FunctionRegistry {

    private final Map<String, FunctionDefinition> functions = new LinkedHashMap<>();

    /** An empty registry. */
    public static FunctionRegistry empty() {
        return new FunctionRegistry();
    }

    /**
     * Registers a function.
     *
     * @throws IllegalArgumentException if the name is already registered
     */
    public synchronized FunctionRegistry register(FunctionDefinition function) {
        String key = function.name().toUpperCase(Locale.ROOT);
        if (functions.putIfAbsent(key, function) != null) {
            throw new IllegalArgumentException("Function " + key + " is already registered");
        }
        return this;
    }

    public synchronized Optional<FunctionDefinition> find(String name) {
        return Optional.ofNullable(functions.get(name.toUpperCase(Locale.ROOT)));
    }

    public synchronized Collection<FunctionDefinition> all() {
        return Collections.unmodifiableCollection(new java.util.ArrayList<>(functions.values()));
    }
}
