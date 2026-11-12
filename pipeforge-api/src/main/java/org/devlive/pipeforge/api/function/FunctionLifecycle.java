/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.function;

import org.devlive.pipeforge.api.annotation.Evolving;

/**
 * Implemented by classes whose {@link ScalarFunction} methods are instance methods. Every parallel
 * instance of an operator creates one object with the public no-argument constructor, calls {@link
 * #open} before the first call and {@link #close} after the last; the object is used by one thread
 * at a time. A class that does not implement it may only declare static functions.
 *
 * <pre>{@code
 * public final class DictLookup implements FunctionLifecycle {
 *     private Map<String, String> dict;
 *
 *     @Override
 *     public void open(FunctionContext context) {
 *         dict = load(context.parameters().get("path"));
 *     }
 *
 *     @ScalarFunction(name = "dict")
 *     public String lookup(String key) {
 *         return dict.get(key);
 *     }
 * }
 * }</pre>
 */
@Evolving
public interface FunctionLifecycle {

    /** Prepares the object, for example loads a dictionary; a failure fails the operator. */
    default void open(FunctionContext context) throws Exception {}

    /** Releases what {@link #open} acquired; called even when {@link #open} failed. */
    default void close() throws Exception {}
}
