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
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;

/**
 * Runs the tasks of a worker. Each task has its own thread; the executor deploys them, routes
 * events to them and enforces the cancel timeouts:
 *
 * <ol>
 *   <li>cancel: the task is woken up and its reader and writer interrupt blocking IO;
 *   <li>after {@code cancelTimeout}: the task thread is interrupted;
 *   <li>after twice {@code cancelTimeout}: the task is reported failed with {@code PF-RES-0005}
 *       and its thread is left behind.
 * </ol>
 */
public final class TaskExecutor implements TaskExecutorGateway, AutoCloseable {

    private final JobMasterGateway master;
    private final Map<ExecutionAttemptId, Task> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog;

    /**
     * @param name the worker name, used in thread names
     * @param master where the tasks report to
     */
    public TaskExecutor(String name, JobMasterGateway master) {
        this.master = master;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "task-watchdog-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
        Task task = new Task(deployment, master, done -> tasks.remove(done.id(), done));
        if (tasks.putIfAbsent(deployment.id(), task) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Task " + deployment.id() + " is already deployed"));
        }
        task.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> cancelTask(ExecutionAttemptId id) {
        Task task = tasks.get(id);
        if (task != null) {
            task.cancel();
            Duration timeout = timeoutOf(task);
            long millis = timeout.toMillis();
            watchdog.schedule(task::interrupt, millis, TimeUnit.MILLISECONDS);
            watchdog.schedule(() -> giveUp(task, millis * 2), millis * 2, TimeUnit.MILLISECONDS);
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

    /** The tasks that have not reported a final state yet. */
    public List<ExecutionAttemptId> runningTasks() {
        return List.copyOf(tasks.keySet());
    }

    /** Cancels the remaining tasks and stops the watchdog. */
    @Override
    public void close() {
        tasks.values().forEach(Task::cancel);
        watchdog.shutdownNow();
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
        task.giveUp(error);
    }
}
