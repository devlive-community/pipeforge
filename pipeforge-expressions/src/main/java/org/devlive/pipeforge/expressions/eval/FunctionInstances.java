/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.eval;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.function.FunctionContext;
import org.devlive.pipeforge.api.function.FunctionLifecycle;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.expressions.ast.Node;
import org.devlive.pipeforge.expressions.function.FunctionCall;
import org.jspecify.annotations.Nullable;

/**
 * The objects of the instance functions that one evaluator calls: one per class, created with the
 * public no-argument constructor and opened with the context before the first row, closed with the
 * evaluator. An evaluator whose functions are all static holds none.
 */
public final class FunctionInstances implements AutoCloseable {

    private final Map<Class<?>, FunctionLifecycle> instances;

    private FunctionInstances(Map<Class<?>, FunctionLifecycle> instances) {
        this.instances = instances;
    }

    /** No instances, for expressions without instance functions. */
    public static FunctionInstances none() {
        return new FunctionInstances(new LinkedHashMap<>());
    }

    /**
     * Creates and opens an object for every class whose instance functions the expressions call.
     * When one cannot be created or opened, the objects opened so far and the failed one are closed.
     *
     * @throws PipeForgeException {@link ErrorCode#PLUGIN_CALL_FAILED}
     */
    public static FunctionInstances open(List<Node> roots, FunctionContext context) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        roots.forEach(root -> collect(root, classes));
        if (classes.isEmpty()) {
            return none();
        }
        Map<Class<?>, FunctionLifecycle> opened = new LinkedHashMap<>();
        FunctionInstances instances = new FunctionInstances(opened);
        for (Class<?> type : classes) {
            FunctionLifecycle instance;
            try {
                instance = (FunctionLifecycle) type.getConstructor().newInstance();
            } catch (InvocationTargetException e) {
                instances.closeAfterFailure();
                throw failed(type, "constructor", e.getCause() == null ? e : e.getCause());
            } catch (ReflectiveOperationException | RuntimeException e) {
                instances.closeAfterFailure();
                throw failed(type, "constructor", e);
            }
            opened.put(type, instance);
            try {
                instance.open(context);
            } catch (Exception e) {
                instances.closeAfterFailure();
                throw failed(type, "open", e);
            }
        }
        return instances;
    }

    private static void collect(Node node, Set<Class<?>> classes) {
        if (node.payload() instanceof FunctionCall call && call.signature().instance()) {
            classes.add(call.signature().method().getDeclaringClass());
        }
        node.args().forEach(arg -> collect(arg, classes));
    }

    /** The object for the instance functions of {@code type}. */
    Object get(Class<?> type) {
        Object instance = instances.get(type);
        if (instance == null) {
            throw new IllegalStateException("No open instance of " + type.getName());
        }
        return instance;
    }

    /** The number of objects. */
    public int size() {
        return instances.size();
    }

    /**
     * Closes every object, in the reverse order of opening.
     *
     * @throws PipeForgeException {@link ErrorCode#PLUGIN_CALL_FAILED} for the first failure, after
     *     every object was closed
     */
    @Override
    public void close() {
        PipeForgeException failure = closeAll();
        if (failure != null) {
            throw failure;
        }
    }

    private void closeAfterFailure() {
        closeAll();
    }

    private @Nullable PipeForgeException closeAll() {
        List<Map.Entry<Class<?>, FunctionLifecycle>> entries = new ArrayList<>(instances.entrySet());
        Collections.reverse(entries);
        PipeForgeException failure = null;
        for (Map.Entry<Class<?>, FunctionLifecycle> entry : entries) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = failed(entry.getKey(), "close", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        instances.clear();
        return failure;
    }

    private static PipeForgeException failed(Class<?> type, String operation, Throwable cause) {
        return PipeForgeException.of(ErrorCode.PLUGIN_CALL_FAILED)
                .param("plugin", type.getName())
                .param("operation", "FunctionLifecycle." + operation)
                .param("reason", cause.getClass().getSimpleName() + ": " + cause.getMessage())
                .cause(cause)
                .build();
    }
}
