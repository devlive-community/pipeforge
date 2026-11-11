/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * The boundary around plugin code. Every call runs with the plugin's class loader as the thread
 * context class loader; failures become errors that name the plugin and the operation.
 *
 * <p>{@link #call} runs on the calling thread, for the data path. {@link #callWithTimeout} runs
 * on a separate thread and interrupts the call when it takes too long, for control operations
 * such as opening connections or listing tables.
 */
public final class PluginInvoker implements AutoCloseable {

    private static final AtomicInteger THREADS = new AtomicInteger();

    private final String plugin;
    private final ClassLoader loader;
    private final ExecutorService executor;

    PluginInvoker(String plugin, ClassLoader loader) {
        this.plugin = plugin;
        this.loader = loader;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "plugin-" + plugin + "-" + THREADS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newCachedThreadPool(factory);
    }

    /** {@code id@version} of the plugin. */
    public String plugin() {
        return plugin;
    }

    public ClassLoader classLoader() {
        return loader;
    }

    /** Runs plugin code on the calling thread with the plugin's context class loader. */
    public <T> T call(String operation, Callable<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.call();
        } catch (PipeForgeException e) {
            throw e;
        } catch (InterruptedException e) {
            thread.interrupt();
            throw failed(operation, e);
        } catch (Exception | LinkageError e) {
            throw failed(operation, e);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Runs plugin code that returns nothing, on the calling thread. */
    public void run(String operation, ThrowingRunnable action) {
        call(operation, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Runs plugin code on a plugin thread and waits at most {@code timeout}; on timeout the thread
     * is interrupted and {@link ErrorCode#PLUGIN_TIMEOUT} is thrown.
     */
    public <T> T callWithTimeout(String operation, Duration timeout, Callable<T> action) {
        Future<T> future = executor.submit(() -> call(operation, action));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw PipeForgeException.of(ErrorCode.PLUGIN_TIMEOUT)
                    .param("plugin", plugin)
                    .param("operation", operation)
                    .param("timeoutMs", timeout.toMillis())
                    .build();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failed(operation, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PipeForgeException pfe) {
                throw pfe;
            }
            throw failed(operation, e.getCause() == null ? e : e.getCause());
        }
    }

    private PipeForgeException failed(String operation, Throwable cause) {
        return PipeForgeException.of(ErrorCode.PLUGIN_CALL_FAILED)
                .param("plugin", plugin)
                .param("operation", operation)
                .param(
                        "reason",
                        cause.getClass().getSimpleName()
                                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()))
                .cause(cause)
                .build();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** A runnable that may throw. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
