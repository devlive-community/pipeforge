/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.ErrorClassifiers;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.jspecify.annotations.Nullable;

/**
 * The boundary around every call into an operator's plugin code: sets the plugin's context class
 * loader and turns what the plugin throws into a {@link PipeForgeException} that names the node
 * and subtask.
 *
 * <ul>
 *   <li>a {@link PipeForgeException} keeps its code; the node and subtask are added to its scope
 *       unless it already names a node (an error from a downstream operator passes through the
 *       upstream operators unchanged);
 *   <li>anything else goes to the plugin's {@link org.devlive.pipeforge.api.capability.ErrorClassifier}
 *       (the created source, sink or transform first, then the factory), and what it returns is
 *       treated like an error the plugin threw;
 *   <li>what no classifier knows becomes {@code PF-SRC-0001} for sources, {@code PF-SINK-0001} for
 *       sinks and {@code PF-PLUG-0007} for transforms, with the original exception as the cause.
 * </ul>
 */
final class OperatorCall {

    /** Plugin code that may throw anything. */
    @FunctionalInterface
    interface Action<T> {
        T run() throws Exception;
    }

    /** Plugin code without a result. */
    @FunctionalInterface
    interface VoidAction {
        void run() throws Exception;
    }

    private final PlannedNode node;
    private final ClassLoader loader;
    private final ErrorScope scope;
    /** The source, sink or transform the factory created, once it did. */
    private volatile @Nullable Object component;

    OperatorCall(PlannedNode node, ClassLoader loader, ErrorScope task) {
        this.node = node;
        this.loader = loader;
        this.scope = task.withNode(node.id());
    }

    String node() {
        return node.id();
    }

    /** Records the source, sink or transform the factory created, which may classify errors. */
    <T> T created(T value) {
        component = value;
        return value;
    }

    ClassLoader classLoader() {
        return loader;
    }

    <T> T call(String operation, Action<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        boolean switched = previous != loader;
        if (switched) {
            thread.setContextClassLoader(loader);
        }
        try {
            return action.run();
        } catch (Exception | LinkageError e) {
            // a LinkageError is a class the plugin needs and does not bring, not a broken JVM
            throw failure(operation, e);
        } finally {
            if (switched) {
                thread.setContextClassLoader(previous);
            }
        }
    }

    void run(String operation, VoidAction action) {
        call(operation, () -> {
            action.run();
            return null;
        });
    }

    /** The error to report for {@code error}, thrown by this operator while doing {@code operation}. */
    PipeForgeException failure(String operation, Throwable error) {
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        if (error instanceof org.devlive.pipeforge.api.row.BadRowException bad) {
            // the plugin says the data is wrong: a bad row for the dirty data policy
            return PipeForgeException.of(ErrorCode.BAD_ROW)
                    .param("node", node.id())
                    .param("reason", String.valueOf(bad.getMessage()))
                    .scope(scope)
                    .cause(bad)
                    .build();
        }
        PipeForgeException known = error instanceof PipeForgeException e ? e : classified(error);
        if (known != null) {
            PipeForgeException e = known;
            if (e.scope().node() != null) {
                return e;
            }
            PipeForgeException.Builder builder = PipeForgeException.of(e.code())
                    .scope(merged(e.scope()))
                    .kind(e.kind())
                    .cause(e.getCause() != null || e == error ? e.getCause() : error);
            e.params().forEach(builder::param);
            return builder.build();
        }
        String reason = reason(error);
        PipeForgeException.Builder builder = node.kind() == NodeKind.TRANSFORM
                ? PipeForgeException.of(ErrorCode.PLUGIN_CALL_FAILED)
                        .param("plugin", node.plugin())
                        .param("operation", operation)
                        .param("reason", reason)
                : PipeForgeException.of(
                                node.kind() == NodeKind.SOURCE ? ErrorCode.SOURCE_FAILED : ErrorCode.SINK_FAILED)
                        .param("node", node.id())
                        .param("reason", operation + ": " + reason);
        return builder.scope(scope).cause(error).build();
    }

    /** What the plugin's classifiers make of {@code error}, asked with the plugin's class loader. */
    private @Nullable PipeForgeException classified(Throwable error) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return ErrorClassifiers.classify(error, component, node.component().factory());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** The scope of a plugin's own error, completed with the job, run, node and subtask of this operator. */
    private ErrorScope merged(ErrorScope own) {
        ErrorScope result = own.withNode(node.id());
        if (own.job() == null && scope.job() != null) {
            result = result.withJob(scope.job());
        }
        if (own.run() == null && scope.run() != null) {
            result = result.withRun(scope.run());
        }
        if (own.subtask() == null && scope.subtask() != null) {
            result = result.withSubtask(scope.subtask());
        }
        return result;
    }

    static String reason(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
