/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One parallel instance of an operator chain, on its own thread, following the mailbox model:
 *
 * <pre>
 * loop:
 *   run the mail (split assignments, cancel, later checkpoint triggers and timers)
 *   reader.pollNext → transforms → sink
 * </pre>
 *
 * <p>Events and data are handled on the same thread, so operators need no locks and a snapshot
 * always sees a consistent state. When the reader has nothing to read the thread waits for mail,
 * backing off from 1 ms up to 100 ms, and wakes up at once when mail arrives.
 *
 * <p>The task reports exactly one final state: {@code FINISHED} with the sink's committable,
 * {@code FAILED} with the error or {@code CANCELED}.
 */
final class Task implements Runnable {

    /**
     * The checkpoint id passed to {@code prepareCommit} when the input ends: above every checkpoint,
     * so that its transaction never shares an id with one, and a writer restored from a checkpoint
     * takes a final transaction that was never committed as one to abort.
     */
    static final long FINAL_CHECKPOINT_ID = Long.MAX_VALUE;

    private static final Logger LOG = LoggerFactory.getLogger(Task.class);
    private static final long MIN_IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long MAX_IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    /** Aligning longer than this means severe backpressure (checkpoint design, section 3.2). */
    static final long ALIGNMENT_WARN_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final TaskDeployment deployment;
    private final JobMasterGateway master;
    private final @Nullable NetworkEnvironment network;
    private final Consumer<Task> onTerminated;
    private final Mailbox mailbox = new Mailbox();
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.DEPLOYING);
    private final ErrorScope scope;
    private final String name;
    private final Thread thread;
    private volatile boolean cancelled;
    private volatile @Nullable OperatorChain chain;
    private volatile long runningSince;
    private volatile long endedAt;
    private final AtomicLong idleNanos = new AtomicLong();
    private final AtomicLong throttleNanos = new AtomicLong();
    private final org.devlive.pipeforge.engine.worker.log.@Nullable LogService logs;
    private final TaskMemory memory;
    private final DirtyRows dirty;
    private volatile @Nullable PluginThreads threads;
    /** Writes the states of checkpoints; {@code null} until the executor sets it. */
    private volatile @Nullable StateUploader uploader;

    Task(TaskDeployment deployment, JobMasterGateway master, Consumer<Task> onTerminated) {
        this(deployment, master, null, onTerminated);
    }

    Task(
            TaskDeployment deployment,
            JobMasterGateway master,
            @Nullable NetworkEnvironment network,
            Consumer<Task> onTerminated) {
        this(deployment, master, network, null, MemoryLimits.DEFAULTS, onTerminated);
    }

    /**
     * @param network the data exchange of the worker, or {@code null} for tasks that need none
     * @param logs where the task writes its own log, or {@code null} for none
     */
    Task(
            TaskDeployment deployment,
            JobMasterGateway master,
            @Nullable NetworkEnvironment network,
            org.devlive.pipeforge.engine.worker.log.@Nullable LogService logs,
            MemoryLimits limits,
            Consumer<Task> onTerminated) {
        this.network = network;
        this.logs = logs;
        this.memory = new TaskMemory(limits);
        this.dirty = new DirtyRows(
                deployment.settings().skipDirtyRows(), deployment.id().toString());
        this.deployment = deployment;
        this.master = master;
        this.onTerminated = onTerminated;
        ExecutionAttemptId id = deployment.id();
        this.scope = ErrorScope.none()
                .withJob(deployment.chain().job().jobName())
                .withRun(id.runId())
                .withSubtask(id.subtask());
        this.name = id.vertexId() + "-" + id.subtask();
        this.thread = new Thread(this, "task-" + name);
        thread.setDaemon(true);
    }

    ExecutionAttemptId id() {
        return deployment.id();
    }

    /** Where the states of this task's checkpoints are written; set before {@link #start}. */
    void uploader(StateUploader uploader) {
        this.uploader = uploader;
    }

    // ------------------------------------------------------------------ checkpoints

    /** The elements of a state this task restores, read from the checkpoint it restores from. */
    private List<byte[]> restoredState(org.devlive.pipeforge.engine.rpc.StateHandle handle) {
        StateUploader states = uploader;
        if (states == null) {
            throw new IllegalStateException("Task " + deployment.id() + " has no state reader");
        }
        TaskDeployment.Restore restore = deployment.restore();
        String directory = restore.directory() != null
                ? restore.directory()
                : org.devlive.pipeforge.engine.api.checkpoint.CheckpointPaths.checkpoint(
                        deployment.id().runId(), restore.checkpointId());
        return states.read(directory, restore.checkpointId(), handle);
    }

    /**
     * Starts a checkpoint at this source task, on the task thread between two reads. A task that
     * does not run yet, or reads no source, declines.
     */
    void triggerCheckpoint(org.devlive.pipeforge.engine.rpc.CheckpointTrigger trigger) {
        OperatorChain operators = chain;
        if (operators == null || state.get() != TaskState.RUNNING || !operators.hasSource()) {
            decline(
                    trigger.checkpointId(),
                    operators == null || state.get() != TaskState.RUNNING
                            ? "the task does not run yet"
                            : "the task reads no source; it takes part when the barrier reaches it");
            return;
        }
        if (trigger.stop()) {
            decline(trigger.checkpointId(), "this worker cannot stop a run with a savepoint");
            return;
        }
        var barrier = new org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier(
                trigger.checkpointId(),
                trigger.timestampMs(),
                trigger.type() == org.devlive.pipeforge.engine.rpc.CheckpointType.SAVEPOINT
                        ? org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier.Type.SAVEPOINT
                        : org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier.Type.CHECKPOINT,
                false);
        mailbox.put(() -> checkpoint(barrier, 0));
    }

    void notifyCheckpointComplete(long checkpointId) {
        mailbox.put(() -> {
            OperatorChain operators = chain;
            if (operators != null) {
                operators.notifyCheckpointComplete(checkpointId);
            }
        });
    }

    void abortCheckpoint(long checkpointId) {
        mailbox.put(() -> {
            OperatorChain operators = chain;
            if (operators != null) {
                operators.abortCheckpoint(checkpointId);
            }
        });
    }

    /**
     * Takes this task's part of a checkpoint, on the task thread: the states first, then the
     * barrier moves on, so the states hold exactly the rows before it. The states are written on
     * the uploader's threads while the task goes on; the master hears of them afterwards.
     */
    @SuppressWarnings("FutureReturnValueIgnored") // the callback reports to the master
    private void checkpoint(
            org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier barrier, long alignmentNanos) {
        OperatorChain operators = chain;
        StateUploader states = uploader;
        long id = barrier.checkpointId();
        if (operators == null || states == null) {
            decline(id, "the task cannot take snapshots");
            return;
        }
        long started = System.nanoTime();
        List<StateUploader.Pending> taken = new java.util.ArrayList<>(operators.snapshot(id));
        operators.barrier(barrier);
        taken.addAll(operators.sinkSnapshot());
        long syncMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        long bytes = taken.stream().mapToLong(p -> p.body().length).sum();
        long uploading = System.nanoTime();
        if (alignmentNanos > ALIGNMENT_WARN_NANOS) {
            LOG.warn(
                    "Task {} waited {} ms for the barriers of checkpoint {}: its inputs are backpressured",
                    deployment.id(),
                    TimeUnit.NANOSECONDS.toMillis(alignmentNanos),
                    id);
        }
        states.upload(deployment.id(), id, taken).whenComplete((handles, error) -> {
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error;
                master.declineCheckpoint(deployment.id(), id, PipeForgeException.wrap(cause));
                return;
            }
            master.acknowledgeCheckpoint(new org.devlive.pipeforge.engine.rpc.CheckpointAck(
                    deployment.id(),
                    id,
                    handles,
                    new org.devlive.pipeforge.engine.rpc.CheckpointTaskMetrics(
                            syncMs,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - uploading),
                            TimeUnit.NANOSECONDS.toMillis(alignmentNanos),
                            bytes)));
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored") // a lost decline ends in the checkpoint's timeout
    private void decline(long checkpointId, String reason) {
        master.declineCheckpoint(
                deployment.id(),
                checkpointId,
                PipeForgeException.of(org.devlive.pipeforge.common.error.ErrorCode.CHECKPOINT_DECLINED)
                        .param("checkpoint", checkpointId)
                        .param("task", deployment.id().toString())
                        .param("reason", reason)
                        .build());
    }

    TaskDeployment deployment() {
        return deployment;
    }

    TaskState state() {
        return state.get();
    }

    void start() {
        thread.start();
    }

    @Override
    public void run() {
        org.devlive.pipeforge.engine.worker.log.LogService.Scope log = openLog();
        try {
            runLogged();
        } finally {
            if (log != null) {
                log.close();
            }
        }
    }

    private org.devlive.pipeforge.engine.worker.log.LogService.@Nullable Scope openLog() {
        org.devlive.pipeforge.engine.worker.log.LogService service = logs;
        if (service == null) {
            return null;
        }
        try {
            // named after the job as its definition names it; the run keeps the path unique
            return service.open(deployment.chain().job().jobName(), deployment.id());
        } catch (java.io.UncheckedIOException e) {
            // the task runs without a log of its own; the worker's log still has its lines
            LOG.warn("Task {} cannot write its own log", deployment.id(), e);
            return null;
        }
    }

    private void runLogged() {
        OperatorChain operators = null;
        // created on the task thread, so that plugin threads take its log tags
        PluginThreads started = new PluginThreads(name, this::failFromPluginThread);
        threads = started;
        try {
            operators = new OperatorChain(
                    deployment, scope, name, master, network, () -> cancelled, memory, started, dirty);
            chain = operators;
            if (cancelled) {
                throw new CancelledException();
            }
            operators.open(this::restoredState);
            operators.onAligned(this::checkpoint);
            runningSince = System.nanoTime();
            transition(TaskState.RUNNING);
            LOG.info("Task {} is running", deployment.id());
            operators.startSource();
            process(operators);
            List<SerializedValue> committables = operators.finish(FINAL_CHECKPOINT_ID);
            OperatorChain closing = operators;
            operators = null;
            closing.close();
            stopThreads(started);
            memory.release();
            LOG.info("Task {} finished", deployment.id());
            report(TaskStateUpdate.finished(deployment.id(), committables));
        } catch (Throwable error) {
            if (operators != null) {
                closeQuietly(operators);
            }
            stopThreads(started);
            memory.release();
            if (cancelled) {
                LOG.info("Task {} was cancelled", deployment.id());
                report(TaskStateUpdate.of(deployment.id(), TaskState.CANCELED));
            } else {
                if (error instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                PipeForgeException failure = PipeForgeException.wrap(error);
                LOG.warn("Task {} failed", deployment.id(), failure);
                report(TaskStateUpdate.failed(deployment.id(), failure));
            }
        }
    }

    private void process(OperatorChain operators) throws Exception {
        long idle = MIN_IDLE_NANOS;
        while (true) {
            mailbox.drain();
            if (cancelled) {
                throw new CancelledException();
            }
            long quota = operators.throttleNanos();
            if (quota > 0) {
                // over the rate limit: wait for the quota, and take mail meanwhile
                long start = System.nanoTime();
                mailbox.waitAndDrain(quota);
                throttleNanos.addAndGet(System.nanoTime() - start);
                continue;
            }
            PollStatus status = operators.pollNext();
            switch (status) {
                case MORE_AVAILABLE -> idle = MIN_IDLE_NANOS;
                case NOTHING_AVAILABLE -> {
                    if (operators.pollWaits()) {
                        // the poll waited for the input already; look at the mail and poll again
                        continue;
                    }
                    long start = System.nanoTime();
                    int handled = mailbox.waitAndDrain(idle);
                    idleNanos.addAndGet(System.nanoTime() - start);
                    if (handled > 0) {
                        idle = MIN_IDLE_NANOS;
                    } else {
                        idle = Math.min(idle * 2, MAX_IDLE_NANOS);
                    }
                }
                case END_OF_INPUT -> {
                    return;
                }
            }
        }
    }

    /** Delivers an event to the source; it runs on the task thread between two reads. */
    void deliver(OperatorEvent event) {
        long bytes = event instanceof OperatorEvent.AddSplits add
                ? add.splits().stream().mapToLong(SerializedValue::size).sum()
                : 0;
        // the splits wait in the mailbox until the task thread hands them to the reader
        memory.splitsQueued(bytes);
        mailbox.put(() -> {
            try {
                OperatorChain operators = chain;
                if (operators != null) {
                    operators.handle(event);
                }
            } finally {
                memory.splitsTaken(bytes);
            }
        });
    }

    Thread thread() {
        return thread;
    }

    /** A plugin thread ended with an uncaught exception: the task fails at its next step. */
    private void failFromPluginThread(PipeForgeException error) {
        if (!state.get().isTerminal()) {
            LOG.warn("A thread of task {} failed", deployment.id(), error);
            mailbox.put(() -> {
                throw error;
            });
        }
    }

    /** Interrupts the plugin threads the plugins did not stop when they closed. */
    private void stopThreads(PluginThreads started) {
        List<Thread> left = started.stop();
        if (!left.isEmpty()) {
            LOG.info(
                    "Task {} interrupted {} plugin thread(s) left after closing: {}",
                    deployment.id(),
                    left.size(),
                    left.stream().map(Thread::getName).toList());
        }
    }

    /** The threads the plugins of the task started, or {@code null} before it ran. */
    @Nullable
    PluginThreads threads() {
        return threads;
    }

    /** The memory the task holds. */
    TaskMemory memory() {
        return memory;
    }

    /**
     * Asks the task to stop: wakes the task thread, and lets the reader and writer interrupt blocking
     * IO. Callable from any thread.
     */
    void cancel() {
        if (state.get().isTerminal()) {
            return;
        }
        cancelled = true;
        mailbox.put(() -> {});
        OperatorChain operators = chain;
        if (operators != null) {
            operators.cancel();
        }
    }

    /** Interrupts the task thread: the fallback when a cancelled task does not stop in time. */
    void interrupt() {
        if (!state.get().isTerminal()) {
            LOG.warn("Task {} did not stop after it was cancelled; interrupting its thread", deployment.id());
            thread.interrupt();
        }
    }

    /**
     * Gives the task up with {@code error} when it has not stopped; its thread is left behind. Returns
     * whether the task was given up.
     */
    boolean giveUp(PipeForgeException error) {
        if (state.get().isTerminal()) {
            return false;
        }
        LOG.error("Task {} is given up: {}", deployment.id(), error.getMessage());
        return report(TaskStateUpdate.failed(deployment.id(), error));
    }

    /**
     * The task's metrics since it started running; callable from any thread. Empty until the task
     * runs; frozen once it ended.
     */
    TaskMetricsSnapshot snapshot() {
        OperatorChain operators = chain;
        long since = runningSince;
        if (operators == null || since == 0) {
            return TaskMetricsSnapshot.EMPTY;
        }
        long end = endedAt;
        long running = (end == 0 ? System.nanoTime() : end) - since;
        return new TaskMetricsSnapshot(
                operators.snapshot(),
                running,
                idleNanos.get(),
                operators.producerWaitNanos(),
                operators.writerWaitNanos(),
                operators.backpressureNanos(),
                operators.bytesSent(),
                operators.bytesReceived(),
                operators.queueDepth(),
                operators.queueCapacity(),
                memory.accounted(),
                dirty.samples(),
                throttleNanos.get());
    }

    private void transition(TaskState next) {
        TaskState current = state.get();
        if (!current.isTerminal() && state.compareAndSet(current, next)) {
            master.updateTaskState(TaskStateUpdate.of(deployment.id(), next));
        }
    }

    /** Reports a final state once; later reports are ignored. */
    private boolean report(TaskStateUpdate update) {
        while (true) {
            TaskState current = state.get();
            if (current.isTerminal()) {
                return false;
            }
            if (state.compareAndSet(current, update.state())) {
                break;
            }
        }
        if (runningSince != 0) {
            endedAt = System.nanoTime();
        }
        master.updateTaskState(update.withMetrics(snapshot()));
        onTerminated.accept(this);
        return true;
    }

    private void closeQuietly(OperatorChain operators) {
        try {
            operators.close();
        } catch (RuntimeException e) {
            LOG.debug("Closing task {} after a failure also failed", deployment.id(), e);
        }
    }

    /** Leaves the processing loop when the task is cancelled. */
    private static final class CancelledException extends Exception {
        private static final long serialVersionUID = 1L;

        CancelledException() {
            super("cancelled", null, false, false);
        }
    }
}
