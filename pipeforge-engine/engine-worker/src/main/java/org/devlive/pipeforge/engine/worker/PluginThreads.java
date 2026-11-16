/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * The threads the plugins of a task start through their context. Each is named after the task and
 * the node ({@code plugin-src-0-reader-1}), runs with the plugin's class loader and the log tags of
 * the task, and is a daemon; an uncaught exception fails the task. When the task ends the remaining
 * threads are interrupted, and the task executor reports those that are still alive as leaked.
 */
final class PluginThreads {

    private final String task;
    private final Consumer<PipeForgeException> onFailure;
    private final @Nullable Map<String, String> logContext = MDC.getCopyOfContextMap();
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicInteger count = new AtomicInteger();

    /**
     * @param task the name of the task, e.g. {@code src-0}
     * @param onFailure receives the error of a thread that ended with an uncaught exception
     */
    PluginThreads(String task, Consumer<PipeForgeException> onFailure) {
        this.task = task;
        this.onFailure = onFailure;
    }

    /** The thread factory a node's plugin gets through its context. */
    ThreadFactory factory(OperatorCall call) {
        return runnable -> {
            String name = "plugin-" + task + "-" + call.node() + "-" + count.incrementAndGet();
            Thread thread = new Thread(
                    () -> {
                        if (logContext != null) {
                            MDC.setContextMap(logContext);
                        }
                        runnable.run();
                    },
                    name);
            thread.setDaemon(true);
            thread.setContextClassLoader(call.classLoader());
            thread.setUncaughtExceptionHandler(
                    (failed, error) -> onFailure.accept(call.failure("run the thread " + failed.getName(), error)));
            synchronized (threads) {
                threads.add(thread);
            }
            return thread;
        };
    }

    /** Interrupts the threads that are still alive and returns them. */
    List<Thread> stop() {
        List<Thread> alive = alive();
        alive.forEach(Thread::interrupt);
        return alive;
    }

    /** The threads that are still alive. */
    List<Thread> alive() {
        synchronized (threads) {
            threads.removeIf(t -> t.getState() == Thread.State.TERMINATED);
            return threads.stream().filter(Thread::isAlive).toList();
        }
    }
}
