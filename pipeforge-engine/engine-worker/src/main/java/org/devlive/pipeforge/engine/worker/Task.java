/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
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
     * The checkpoint id passed to {@code prepareCommit} when the input ends. A run without
     * checkpoints commits once, as checkpoint 1.
     */
    static final long FINAL_CHECKPOINT_ID = 1;

    private static final Logger LOG = LoggerFactory.getLogger(Task.class);
    private static final long MIN_IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long MAX_IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final TaskDeployment deployment;
    private final JobMasterGateway master;
    private final Consumer<Task> onTerminated;
    private final Mailbox mailbox = new Mailbox();
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.DEPLOYING);
    private final ErrorScope scope;
    private final String name;
    private final Thread thread;
    private volatile boolean cancelled;
    private volatile @Nullable OperatorChain chain;

    Task(TaskDeployment deployment, JobMasterGateway master, Consumer<Task> onTerminated) {
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
        OperatorChain operators = null;
        try {
            operators = new OperatorChain(deployment, scope, name, master);
            chain = operators;
            if (cancelled) {
                throw new CancelledException();
            }
            operators.open();
            transition(TaskState.RUNNING);
            LOG.info("Task {} is running", deployment.id());
            operators.startSource();
            process(operators);
            Optional<SerializedValue> committable = operators.finish(FINAL_CHECKPOINT_ID);
            OperatorChain closing = operators;
            operators = null;
            closing.close();
            report(TaskStateUpdate.finished(
                    deployment.id(), committable.map(List::of).orElse(List.of())));
        } catch (Throwable error) {
            if (operators != null) {
                closeQuietly(operators);
            }
            if (cancelled) {
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
            PollStatus status = operators.pollNext();
            switch (status) {
                case MORE_AVAILABLE -> idle = MIN_IDLE_NANOS;
                case NOTHING_AVAILABLE -> {
                    if (mailbox.waitAndDrain(idle) > 0) {
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
        mailbox.put(() -> {
            OperatorChain operators = chain;
            if (operators != null) {
                operators.handle(event);
            }
        });
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

    /** Time the task thread blocked on a full write queue. */
    long producerWaitNanos() {
        OperatorChain operators = chain;
        return operators == null ? 0 : operators.producerWaitNanos();
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
        master.updateTaskState(update);
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
