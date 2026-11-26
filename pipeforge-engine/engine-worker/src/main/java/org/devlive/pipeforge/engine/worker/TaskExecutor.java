/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.SlotRequest;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.jspecify.annotations.Nullable;

/**
 * Runs the tasks of a worker. Each task has its own thread; the executor deploys them, routes
 * events to them and enforces the cancel timeouts:
 *
 * <ol>
 *   <li>cancel: the task is woken up and its reader and writer interrupt blocking IO;
 *   <li>after {@code cancelTimeout}: the task thread is interrupted;
 *   <li>after the cancel kill timeout (twice {@code cancelTimeout} unless {@link
 *       Options#cancelKillTimeout()} is set): the task is reported failed with {@code PF-RES-0005},
 *       its thread is left behind and reported with where it hangs.
 * </ol>
 *
 * <p>Threads the plugins of a task started through their context and that are still alive {@link
 * Options#leakGrace()} after the task ended are reported as leaked, as is the thread of a task that
 * was given up.
 */
public final class TaskExecutor implements TaskExecutorGateway, LocalTasks, AutoCloseable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TaskExecutor.class);

    private final JobMasterGateway master;
    private final @Nullable SlotTable slots;
    private final @Nullable NetworkEnvironment network;
    private final org.devlive.pipeforge.engine.worker.log.@Nullable LogService logs;
    private final MemoryLimits limits;
    private final Options options;
    private final StateUploader uploader;
    private final java.util.List<String> leaked = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final String name;
    private volatile java.util.function.Consumer<String> runEnded = run -> {};
    private final Map<ExecutionAttemptId, Task> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog;
    private final Map<ExecutionAttemptId, ScheduledFuture<?>> reporters = new ConcurrentHashMap<>();
    private final HeapGuard heapGuard;

    /**
     * @param name the worker name, used in thread names
     * @param master where the tasks report to
     */
    /** Without slots: tasks run as they are deployed, as in local runs. */
    public TaskExecutor(String name, JobMasterGateway master) {
        this(name, master, null);
    }

    /**
     * With the slots of a worker: tasks run in slots that a job master reserved, and reservations
     * that no task used are released after {@link SlotTable#UNUSED_TIMEOUT}.
     */
    public TaskExecutor(String name, JobMasterGateway master, @Nullable SlotTable slots) {
        this(name, master, slots, null);
    }

    /**
     * With the data exchange of the worker, so that tasks can read from and write to other tasks.
     *
     * @param network the data exchange, which the caller starts and closes; {@code null} for none
     */
    public TaskExecutor(
            String name, JobMasterGateway master, @Nullable SlotTable slots, @Nullable NetworkEnvironment network) {
        this(name, master, slots, network, null);
    }

    /**
     * With task logs: every task writes its own log, which {@link #readLog} reads.
     *
     * @param logs the task logs, which the caller closes; {@code null} for none
     */
    public TaskExecutor(
            String name,
            JobMasterGateway master,
            @Nullable SlotTable slots,
            @Nullable NetworkEnvironment network,
            org.devlive.pipeforge.engine.worker.log.@Nullable LogService logs) {
        this(name, master, slots, network, logs, Options.DEFAULTS);
    }

    /**
     * What a worker sets for its tasks.
     *
     * @param memory the largest row and the memory of a task's write buffer
     * @param cancelKillTimeout how long after a cancel a task that did not stop is given up; {@code
     *     null} for twice the cancel timeout of its job
     * @param leakGrace how long after a task ended its plugin threads may take to end
     */
    public record Options(
            MemoryLimits memory,
            @Nullable Duration cancelKillTimeout,
            Duration leakGrace,
            org.devlive.pipeforge.engine.api.checkpoint.@Nullable CheckpointStorage checkpointStorage,
            int inlineThreshold,
            HeapGuard.Settings heapGuard) {

        /** Default memory limits, the kill timeout of local runs, one second of grace, no heap guard. */
        public static final Options DEFAULTS = new Options(
                MemoryLimits.DEFAULTS,
                null,
                Duration.ofSeconds(1),
                null,
                org.devlive.pipeforge.engine.rpc.CheckpointFiles.DEFAULT_INLINE_THRESHOLD,
                HeapGuard.Settings.DISABLED);

        public Options {
            java.util.Objects.requireNonNull(memory, "memory");
            java.util.Objects.requireNonNull(leakGrace, "leakGrace");
            java.util.Objects.requireNonNull(heapGuard, "heapGuard");
        }

        public static Options of(MemoryLimits memory) {
            return new Options(
                    memory, null, DEFAULTS.leakGrace(), null, DEFAULTS.inlineThreshold(), DEFAULTS.heapGuard());
        }

        public Options withCancelKillTimeout(Duration timeout) {
            return new Options(memory, timeout, leakGrace, checkpointStorage, inlineThreshold, heapGuard);
        }

        public Options withLeakGrace(Duration grace) {
            return new Options(memory, cancelKillTimeout, grace, checkpointStorage, inlineThreshold, heapGuard);
        }

        /** Where the tasks write states larger than the inline threshold. */
        public Options withCheckpointStorage(org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage storage) {
            return new Options(memory, cancelKillTimeout, leakGrace, storage, inlineThreshold, heapGuard);
        }

        /** States up to this size travel in the acknowledgement; larger ones become files. */
        public Options withInlineThreshold(int bytes) {
            return new Options(memory, cancelKillTimeout, leakGrace, checkpointStorage, bytes, heapGuard);
        }

        /** Guards the heap of the worker against the run that holds the most memory; checked every second. */
        public Options withHeapGuard(HeapGuard.Settings settings) {
            return new Options(memory, cancelKillTimeout, leakGrace, checkpointStorage, inlineThreshold, settings);
        }
    }

    /** With options for the tasks. */
    @SuppressWarnings("FutureReturnValueIgnored") // runs until the watchdog stops
    public TaskExecutor(
            String name,
            JobMasterGateway master,
            @Nullable SlotTable slots,
            @Nullable NetworkEnvironment network,
            org.devlive.pipeforge.engine.worker.log.@Nullable LogService logs,
            Options options) {
        this.options = options;
        this.uploader = new StateUploader(options.checkpointStorage(), options.inlineThreshold(), name);
        this.limits = options.memory();
        this.name = name;
        this.logs = logs;
        this.master = master;
        this.slots = slots;
        this.network = network;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "task-watchdog-" + name);
            thread.setDaemon(true);
            return thread;
        });
        if (slots != null) {
            watchdog.scheduleWithFixedDelay(this::releaseUnusedSlots, 1, 1, TimeUnit.SECONDS);
        }
        this.heapGuard = guard(options.heapGuard(), HeapGuard.Heap.jvm(), System::nanoTime);
        if (options.heapGuard().enabled()) {
            watchdog.scheduleWithFixedDelay(this::checkHeap, 1, 1, TimeUnit.SECONDS);
        }
    }

    /** A heap guard over the tasks of this executor; tests check it by hand with their own heap and clock. */
    HeapGuard guard(HeapGuard.Settings settings, HeapGuard.Heap heap, java.util.function.LongSupplier nanos) {
        return new HeapGuard(name, settings, heap, nanos, tasks::values, this::failTask);
    }

    private void checkHeap() {
        try {
            heapGuard.check();
        } catch (RuntimeException e) {
            LOG.warn("The heap guard of worker {} failed", name, e);
        }
    }

    /**
     * How often the heap guard acted since the worker started, by action: {@code pause} (a run
     * stopped reading) and {@code fail} (a run's tasks failed).
     */
    public Map<String, Long> heapGuardActions() {
        return heapGuard.actions();
    }

    private void releaseUnusedSlots() {
        SlotTable table = slots;
        if (table != null) {
            for (SlotRequest released : table.expire()) {
                LOG.info(
                        "Released slot {} of job {}: no task arrived within {} s",
                        released.slot(),
                        released.jobId(),
                        SlotTable.UNUSED_TIMEOUT.toSeconds());
            }
        }
    }

    @Override
    public CompletableFuture<Void> requestSlot(SlotRequest request) {
        if (slots == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("This task executor runs tasks without reserved slots"));
        }
        try {
            slots.reserve(request);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<Void> freeSlot(String allocationId) {
        if (slots != null) {
            slots.free(allocationId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
        if (slots != null && deployment.allocationId() != null) {
            try {
                slots.use(deployment.allocationId());
            } catch (IllegalStateException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        Task task = new Task(deployment, master, network, logs, limits, this::terminated);
        task.uploader(uploader);
        if (tasks.putIfAbsent(deployment.id(), task) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Task " + deployment.id() + " is already deployed"));
        }
        long interval = deployment.settings().metricsInterval().toMillis();
        reporters.put(
                deployment.id(),
                watchdog.scheduleAtFixedRate(() -> report(task), interval, interval, TimeUnit.MILLISECONDS));
        task.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> cancelTask(ExecutionAttemptId id) {
        Task task = tasks.get(id);
        if (task != null) {
            task.cancel();
            enforceCancelTimeouts(task);
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Fails a running task with {@code error}: it stops as a cancelled one does, within the same timeouts. */
    void failTask(Task task, PipeForgeException error) {
        task.fail(error);
        enforceCancelTimeouts(task);
    }

    @SuppressWarnings("FutureReturnValueIgnored") // the task reports its end
    private void enforceCancelTimeouts(Task task) {
        long millis = timeoutOf(task).toMillis();
        Duration kill = options.cancelKillTimeout();
        long killMillis = Math.max(millis, kill == null ? millis * 2 : kill.toMillis());
        try {
            watchdog.schedule(task::interrupt, millis, TimeUnit.MILLISECONDS);
            watchdog.schedule(() -> giveUp(task, killMillis), killMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // the executor is closing
        }
    }

    /** Starts a checkpoint at a source task; triggers for tasks not here are ignored. */
    @Override
    public CompletableFuture<Void> triggerCheckpoint(org.devlive.pipeforge.engine.rpc.CheckpointTrigger trigger) {
        Task task = tasks.get(trigger.task());
        if (task != null) {
            task.triggerCheckpoint(trigger);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> notifyCheckpointComplete(ExecutionAttemptId id, long checkpointId) {
        Task task = tasks.get(id);
        if (task != null) {
            task.notifyCheckpointComplete(checkpointId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> abortCheckpoint(ExecutionAttemptId id, long checkpointId, String reason) {
        Task task = tasks.get(id);
        if (task != null) {
            task.abortCheckpoint(checkpointId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendOperatorEvent(ExecutionAttemptId id, OperatorEvent event) {
        Task task = tasks.get(id);
        if (task != null) {
            task.deliver(event);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> readLog(
            ExecutionAttemptId task,
            org.devlive.pipeforge.engine.rpc.LogQuery query,
            java.util.function.Consumer<org.devlive.pipeforge.engine.rpc.LogChunk> chunks) {
        org.devlive.pipeforge.engine.worker.log.LogService service = logs;
        if (service == null) {
            return CompletableFuture.failedFuture(org.devlive.pipeforge.common.error.PipeForgeException.of(
                            org.devlive.pipeforge.common.error.ErrorCode.TASK_LOG_NOT_FOUND)
                    .param("task", task.toString())
                    .param("worker", name)
                    .build());
        }
        return service.read(task, query, chunks);
    }

    private void report(Task task) {
        if (task.state() == TaskState.RUNNING) {
            master.reportMetrics(task.id(), task.snapshot());
        }
    }

    /** The memory the running tasks hold, in bytes. */
    public long accountedMemory() {
        return tasks.values().stream().mapToLong(t -> t.memory().accounted()).sum();
    }

    private void terminated(Task task) {
        tasks.remove(task.id(), task);
        long left = task.memory().accounted();
        if (left != 0) {
            LOG.warn(
                    "Task {} ended but still holds {} bytes: {}",
                    task.id(),
                    left,
                    task.memory().byHolder());
        }
        ScheduledFuture<?> reporter = reporters.remove(task.id());
        if (reporter != null) {
            reporter.cancel(false);
        }
        try {
            var unused = watchdog.schedule(
                    () -> reportLeakedThreads(task), options.leakGrace().toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // the executor is closing
        }
        String run = task.id().runId();
        if (tasks.keySet().stream().noneMatch(id -> id.runId().equals(run))) {
            try {
                runEnded.accept(run);
            } catch (RuntimeException e) {
                LOG.warn("Releasing the resources of run {} failed", run, e);
            }
        }
    }

    /**
     * Learns when the last task of a run on this executor has ended, for example to release the
     * plugins of the run; called on the thread of that task.
     */
    public void onRunEnded(java.util.function.Consumer<String> listener) {
        this.runEnded = listener;
    }

    private void reportLeakedThreads(Task task) {
        PluginThreads threads = task.threads();
        if (threads == null) {
            return;
        }
        for (Thread thread : threads.alive()) {
            leaked.add(thread.getName());
            LOG.warn(
                    "Thread {} of task {} is still alive {} ms after the task ended; the plugin did not stop it: {}",
                    thread.getName(),
                    task.id(),
                    options.leakGrace().toMillis(),
                    where(thread));
        }
    }

    /** The top of the stack of {@code thread}, to find where it hangs. */
    static String where(Thread thread) {
        StackTraceElement[] stack = thread.getStackTrace();
        return java.util.Arrays.stream(stack)
                .limit(8)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(" <- "));
    }

    /**
     * The threads reported as leaked: plugin threads alive after their task ended and the threads
     * of tasks that were given up, by name.
     */
    public java.util.List<String> leakedThreads() {
        return java.util.List.copyOf(leaked);
    }

    /** The tasks that have not reported a final state yet. */
    public List<ExecutionAttemptId> runningTasks() {
        return List.copyOf(tasks.keySet());
    }

    @Override
    public Map<ExecutionAttemptId, TaskState> taskStates() {
        Map<ExecutionAttemptId, TaskState> states = new java.util.LinkedHashMap<>();
        tasks.forEach((id, task) -> states.put(id, task.state()));
        return states;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored") // cancelling completes at once; the task reports its end
    public void cancelAll() {
        runningTasks().forEach(this::cancelTask);
    }

    /** Cancels the remaining tasks and stops the watchdog. */
    @Override
    public void close() {
        tasks.values().forEach(Task::cancel);
        watchdog.shutdownNow();
        uploader.close();
    }

    private static Duration timeoutOf(Task task) {
        return task.deployment().settings().cancelTimeout();
    }

    private void giveUp(Task task, long waitedMillis) {
        ExecutionAttemptId id = task.id();
        PipeForgeException error = PipeForgeException.of(ErrorCode.CANCEL_TIMEOUT)
                .param("node", id.vertexId())
                .param("subtask", id.subtask())
                .param("timeoutMs", waitedMillis)
                .scope(ErrorScope.node(id.vertexId()).withRun(id.runId()).withSubtask(id.subtask()))
                .build();
        // recorded before the end is reported, so that whoever learns of the end also sees the thread
        Thread stuck = task.thread();
        leaked.add(stuck.getName());
        if (!task.giveUp(error)) {
            leaked.remove(stuck.getName());
        } else {
            LOG.error(
                    "Task {} ignored cancel and interrupt for {} ms; its thread {} is left behind at {}",
                    id,
                    waitedMillis,
                    stuck.getName(),
                    where(stuck));
            PluginThreads threads = task.threads();
            if (threads != null) {
                threads.stop();
            }
        }
    }
}
