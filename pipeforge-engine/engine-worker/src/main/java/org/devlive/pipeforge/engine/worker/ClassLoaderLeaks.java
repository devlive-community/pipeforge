/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follows the plugin class loaders of runs that ended until the garbage collector takes them. A
 * loader that something still holds keeps every class of its plugin in metaspace; after enough of
 * them the worker runs out of it. So a loader still reachable {@code timeout} after its run ended,
 * and after a garbage collection asked for then, is reported as leaked once: with its plugin, its
 * run and the threads that hold it, by their context class loader, their class or the plugin code
 * on their stack. When no thread holds it, a static cache or a JVM-wide registration does (a JDBC
 * driver, a shutdown hook, a thread local, a logging or bean cache).
 *
 * <p>Only weak references to the loaders are kept, so watching them never keeps one alive.
 */
public final class ClassLoaderLeaks {

    private static final Logger LOG = LoggerFactory.getLogger(ClassLoaderLeaks.class);

    /** How long after a run ended its loaders may take to be collected: one minute. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    /**
     * A loader that was not collected in time.
     *
     * @param plugin {@code id@version} of its plugin
     * @param run the run it was loaded for
     * @param holders the threads that hold it, with how; empty when none does
     */
    public record Leak(String plugin, String run, List<String> holders) {}

    private record Watched(WeakReference<ClassLoader> loader, String name, String plugin, String run, long due) {}

    private final Duration timeout;
    private final LongSupplier nanos;
    private final Runnable gc;
    private final List<Watched> watched = new ArrayList<>();
    private final List<Leak> leaks = new CopyOnWriteArrayList<>();
    /** When a collection was asked for because loaders were due; 0 when none was. */
    private long collectedAt;

    /** With the default timeout, the system clock and {@link System#gc}. */
    public ClassLoaderLeaks() {
        this(DEFAULT_TIMEOUT, System::nanoTime, System::gc);
    }

    /** With {@code timeout}, the system clock and {@link System#gc}. */
    public ClassLoaderLeaks(Duration timeout) {
        this(timeout, System::nanoTime, System::gc);
    }

    /**
     * @param nanos the clock
     * @param gc asks for a garbage collection; called at most once per {@link #check}, and only
     *     when a loader is due
     */
    ClassLoaderLeaks(Duration timeout, LongSupplier nanos, Runnable gc) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("the class loader leak timeout must not be negative");
        }
        this.timeout = timeout;
        this.nanos = nanos;
        this.gc = gc;
    }

    /** Starts following a loader that was closed because its run ended. */
    public synchronized void watch(ClassLoader loader, String plugin, String run) {
        watched.add(new Watched(
                new WeakReference<>(loader),
                String.valueOf(loader.getName()),
                plugin,
                run,
                nanos.getAsLong() + timeout.toNanos()));
    }

    /**
     * Forgets the loaders that were collected and reports those still reachable after their timeout
     * and a garbage collection since; returns the leaks found now. Called by one thread at a time,
     * every few seconds.
     */
    public synchronized List<Leak> check() {
        long now = nanos.getAsLong();
        boolean due = false;
        List<Leak> found = new ArrayList<>();
        for (Iterator<Watched> it = watched.iterator(); it.hasNext(); ) {
            Watched entry = it.next();
            ClassLoader loader = entry.loader().get();
            if (loader == null) {
                it.remove();
                continue;
            }
            if (now - entry.due() < 0) {
                continue;
            }
            if (collectedAt == 0 || collectedAt - entry.due() < 0) {
                // a loader nothing holds may just not have been collected yet: ask for it first
                due = true;
                continue;
            }
            it.remove();
            Leak leak = new Leak(entry.plugin(), entry.run(), holders(loader, entry.name()));
            loader = null;
            leaks.add(leak);
            found.add(leak);
            LOG.warn(
                    "The class loader of plugin {} for run {} was not collected {} s after the run ended; {}."
                            + " The worker has {} leaked class loader(s)",
                    leak.plugin(),
                    leak.run(),
                    timeout.toSeconds(),
                    leak.holders().isEmpty()
                            ? "no thread holds it, so a static cache or a JVM-wide registration of the plugin does"
                                    + " (a JDBC driver, a shutdown hook, a thread local)"
                            : "held by " + String.join("; ", leak.holders()),
                    leaks.size());
        }
        if (due) {
            gc.run();
            collectedAt = nanos.getAsLong();
        }
        return found;
    }

    /** The loaders reported as leaked since the worker started. */
    public List<Leak> leaks() {
        return List.copyOf(leaks);
    }

    /** The loaders followed now, collected or not. */
    public synchronized int watching() {
        return watched.size();
    }

    /**
     * The live threads that hold {@code loader}: as their context class loader, through their own
     * class, or with code of a loader named {@code name} on their stack.
     */
    static List<String> holders(ClassLoader loader, String name) {
        List<String> holders = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry :
                Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            String how = how(thread, entry.getValue(), loader, name);
            if (how != null) {
                holders.add("thread " + thread.getName() + " (" + how + ")");
            }
        }
        holders.sort(null);
        return holders;
    }

    private static @Nullable String how(Thread thread, StackTraceElement[] stack, ClassLoader loader, String name) {
        if (thread.getContextClassLoader() == loader) {
            return "its context class loader";
        }
        if (thread.getClass().getClassLoader() == loader) {
            return "its class " + thread.getClass().getName();
        }
        for (StackTraceElement frame : stack) {
            if (name.equals(frame.getClassLoaderName())) {
                // a loader of the same plugin for another run has the same name
                return "runs code of the plugin: " + frame.getClassName() + "." + frame.getMethodName();
            }
        }
        return null;
    }
}
