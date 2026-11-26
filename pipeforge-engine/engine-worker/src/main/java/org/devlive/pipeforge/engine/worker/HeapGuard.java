/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps one job from running the heap of a worker full and taking every job on it down. Checked
 * once a second, it blames the run whose running tasks on this worker account the most memory:
 *
 * <ul>
 *   <li>at {@link Settings#pausePercent()} of the maximum heap, the source tasks of that run stop
 *       reading until the heap is below it again, so that the rows it holds drain;
 *   <li>at {@link Settings#failPercent()} for {@link Settings#failAfter()} on end, the tasks of that
 *       run on this worker fail with {@code PF-RES-0002}, which frees what they hold; the other
 *       runs go on.
 * </ul>
 *
 * <p>The heap is read as used over maximum, garbage included: a full heap the collector keeps
 * freeing does not stay above the failure line for long, a heap of live rows does. When no running
 * task accounts any memory, no run is to blame and the guard only logs.
 */
public final class HeapGuard {

    private static final Logger LOG = LoggerFactory.getLogger(HeapGuard.class);

    /**
     * When the guard acts.
     *
     * @param enabled whether the guard runs at all ({@code worker.heap-guard.enabled})
     * @param pausePercent the heap use, in percent of the maximum, at which the run holding the most
     *     stops reading
     * @param failPercent the heap use at which that run fails once it lasted {@code failAfter}
     * @param failAfter how long the heap must stay at {@code failPercent} before a run fails
     */
    public record Settings(boolean enabled, int pausePercent, int failPercent, Duration failAfter) {

        /** 85% to pause, 92% for 10 seconds to fail. */
        public static final Settings DEFAULTS = new Settings(true, 85, 92, Duration.ofSeconds(10));

        /** Never acts: for local runs and tests that do not need it. */
        public static final Settings DISABLED = new Settings(false, 85, 92, Duration.ofSeconds(10));

        public Settings {
            Objects.requireNonNull(failAfter, "failAfter");
            if (pausePercent <= 0 || pausePercent > failPercent || failPercent > 100) {
                throw new IllegalArgumentException("the heap guard needs 0 < pause percent <= fail percent <= 100, got "
                        + pausePercent + " and " + failPercent);
            }
            if (failAfter.isNegative()) {
                throw new IllegalArgumentException("the heap guard's fail-after must not be negative");
            }
        }
    }

    /** How full the heap is. */
    interface Heap {
        long used();

        long max();

        /** The heap of this JVM; its committed size when it has no maximum. */
        static Heap jvm() {
            var memory = ManagementFactory.getMemoryMXBean();
            return new Heap() {
                @Override
                public long used() {
                    return memory.getHeapMemoryUsage().getUsed();
                }

                @Override
                public long max() {
                    MemoryUsage usage = memory.getHeapMemoryUsage();
                    return usage.getMax() > 0 ? usage.getMax() : usage.getCommitted();
                }
            };
        }
    }

    private final String worker;
    private final Settings settings;
    private final Heap heap;
    private final LongSupplier nanos;
    private final Supplier<Collection<Task>> tasks;
    private final BiConsumer<Task, PipeForgeException> fail;
    private final Set<Task> held = new HashSet<>();
    private final AtomicLong pauses = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private @Nullable String pausedRun;
    /** When the heap reached the failure line, by {@link #nanos}; 0 while it is below. */
    private long fullSince;

    private boolean warnedUnaccounted;

    /**
     * @param worker the worker's name, for the error
     * @param nanos the clock, {@link System#nanoTime} outside tests
     * @param tasks the tasks of the worker
     * @param fail fails a task with an error, freeing what it holds
     */
    HeapGuard(
            String worker,
            Settings settings,
            Heap heap,
            LongSupplier nanos,
            Supplier<Collection<Task>> tasks,
            BiConsumer<Task, PipeForgeException> fail) {
        this.worker = worker;
        this.settings = settings;
        this.heap = heap;
        this.nanos = nanos;
        this.tasks = tasks;
        this.fail = fail;
    }

    /** Looks at the heap once and acts; called by one thread at a time. */
    synchronized void check() {
        if (!settings.enabled()) {
            return;
        }
        long max = heap.max();
        if (max <= 0) {
            return;
        }
        long used = heap.used();
        int percent = (int) Math.min(100, used * 100 / max);
        held.removeIf(task -> task.state().isTerminal());
        long now = nanos.getAsLong();

        if (percent < settings.failPercent()) {
            fullSince = 0;
        } else if (fullSince == 0) {
            fullSince = now == 0 ? 1 : now;
        }
        if (percent < settings.pausePercent()) {
            resume();
            warnedUnaccounted = false;
            return;
        }
        Map<String, List<Task>> runs = runningByRun();
        Map.Entry<String, Long> top = largest(runs);
        if (top == null) {
            if (!warnedUnaccounted) {
                warnedUnaccounted = true;
                LOG.warn(
                        "The heap of worker {} is {}% full ({} of {} bytes), but no running task accounts any memory;"
                                + " the heap guard has no run to slow down",
                        worker, percent, used, max);
            }
            return;
        }
        String run = top.getKey();
        if (fullSince != 0 && now - fullSince >= settings.failAfter().toNanos()) {
            failRun(run, runs.get(run), top.getValue(), used, max);
            fullSince = 0;
            return;
        }
        pause(run, runs.get(run), top.getValue(), percent);
    }

    private void pause(String run, List<Task> tasks, long accounted, int percent) {
        if (!run.equals(pausedRun)) {
            resume();
            pausedRun = run;
            pauses.incrementAndGet();
            LOG.warn(
                    "The heap of worker {} is {}% full; run {} holds the most memory ({} bytes) and stops reading"
                            + " until the heap is below {}%",
                    worker, percent, run, accounted, settings.pausePercent());
        }
        // tasks of the run deployed since it was paused are held back too
        for (Task task : tasks) {
            if (held.add(task)) {
                task.holdBack(true);
            }
        }
    }

    private void resume() {
        if (pausedRun != null) {
            LOG.info(
                    "The heap of worker {} is below {}% again; run {} reads on",
                    worker, settings.pausePercent(), pausedRun);
            pausedRun = null;
        }
        held.forEach(task -> task.holdBack(false));
        held.clear();
    }

    private void failRun(String run, List<Task> tasks, long accounted, long used, long max) {
        failures.incrementAndGet();
        long seconds = settings.failAfter().toSeconds();
        LOG.error(
                "The heap of worker {} stayed {}% full or more for {} s ({} of {} bytes); failing the {} task(s) of"
                        + " run {}, which hold the most memory ({} bytes)",
                worker, settings.failPercent(), seconds, used, max, tasks.size(), run, accounted);
        for (Task task : tasks) {
            ExecutionAttemptId id = task.id();
            PipeForgeException error = PipeForgeException.of(ErrorCode.OUT_OF_MEMORY)
                    .param("worker", worker)
                    .param("percent", settings.failPercent())
                    .param("seconds", seconds)
                    .param("run", run)
                    .param("bytes", accounted)
                    .scope(ErrorScope.node(id.vertexId()).withRun(id.runId()).withSubtask(id.subtask()))
                    .build();
            if (held.remove(task)) {
                task.holdBack(false);
            }
            fail.accept(task, error);
        }
        if (run.equals(pausedRun)) {
            resume();
        }
    }

    /** The running tasks, by their run. */
    private Map<String, List<Task>> runningByRun() {
        Map<String, List<Task>> runs = new LinkedHashMap<>();
        for (Task task : tasks.get()) {
            if (task.state() == TaskState.RUNNING) {
                runs.computeIfAbsent(task.id().runId(), r -> new ArrayList<>()).add(task);
            }
        }
        return runs;
    }

    /** The run whose tasks account the most memory, with how much; {@code null} when none holds any. */
    private static Map.@Nullable Entry<String, Long> largest(Map<String, List<Task>> runs) {
        Map.Entry<String, Long> top = null;
        for (var run : runs.entrySet()) {
            long bytes = run.getValue().stream()
                    .mapToLong(t -> t.memory().accounted())
                    .sum();
            if (bytes > 0 && (top == null || bytes > top.getValue())) {
                top = Map.entry(run.getKey(), bytes);
            }
        }
        return top;
    }

    /** How often the guard acted, by action: {@code pause} and {@code fail}. */
    Map<String, Long> actions() {
        Map<String, Long> actions = new LinkedHashMap<>();
        actions.put("pause", pauses.get());
        actions.put("fail", failures.get());
        return actions;
    }

    /** The run whose sources are held back now, or {@code null}. */
    synchronized @Nullable String pausedRun() {
        return pausedRun;
    }
}
