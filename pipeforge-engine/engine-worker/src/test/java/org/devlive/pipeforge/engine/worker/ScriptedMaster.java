/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.InProcessRpc;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.ReaderMessage;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskSettings;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.testing.TestConnectors;

/**
 * A job master for worker tests: hands out a fixed list of splits on request, then signals the end,
 * and records every state update. It is served over the in-process RPC like the real one.
 */
final class ScriptedMaster implements JobMasterGateway, AutoCloseable {

    final BlockingQueue<TaskStateUpdate> updates = new LinkedBlockingQueue<>();
    final BlockingQueue<ReaderMessage> messages = new LinkedBlockingQueue<>();
    private final Deque<SerializedValue> splits = new ArrayDeque<>();
    private final InProcessRpc.Endpoint<JobMasterGateway> endpoint;
    final TaskExecutor executor;

    ScriptedMaster(List<SerializedValue> splits) {
        this.splits.addAll(splits);
        endpoint = InProcessRpc.serve("test-master", this);
        executor = new TaskExecutor("test", endpoint.gateway());
    }

    /** Ten rows in two splits. */
    static ScriptedMaster tenRows() {
        return new ScriptedMaster(List.of(TestConnectors.split("a", 0, 5), TestConnectors.split("b", 5, 10)));
    }

    static TaskDeployment deployment(JobPlan plan, TaskSettings settings) {
        List<PlannedNode> nodes = plan.nodes();
        ChainDescriptor chain = new ChainDescriptor(
                nodes.get(0).id(),
                new org.devlive.pipeforge.api.factory.JobContext(plan.name(), 1, plan.timeZone()),
                nodes);
        return new TaskDeployment(new ExecutionAttemptId("run-1", nodes.get(0).id(), 0, 0), chain, 1, settings);
    }

    static TaskSettings settings() {
        return TaskSettings.defaults();
    }

    @Override
    public CompletableFuture<Void> updateTaskState(TaskStateUpdate update) {
        updates.add(update);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> sendReaderMessage(ReaderMessage message) {
        messages.add(message);
        if (message instanceof ReaderMessage.SplitRequest request) {
            SerializedValue next = splits.poll();
            OperatorEvent event = next == null
                    ? new OperatorEvent.NoMoreSplits(request.node())
                    : new OperatorEvent.AddSplits(request.node(), List.of(next));
            executor.sendOperatorEvent(request.task(), event);
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Waits for the final state of the task and returns it. */
    TaskStateUpdate awaitEnd() throws InterruptedException {
        while (true) {
            TaskStateUpdate update = updates.poll(20, TimeUnit.SECONDS);
            if (update == null) {
                throw new AssertionError("The task did not end");
            }
            if (update.state().isTerminal()) {
                return update;
            }
        }
    }

    /** Waits until the task reports RUNNING. */
    void awaitRunning() throws InterruptedException {
        TaskStateUpdate update = updates.poll(20, TimeUnit.SECONDS);
        if (update == null || update.state() != TaskState.RUNNING) {
            throw new AssertionError("Expected RUNNING but got " + update);
        }
    }

    ExecutionAttemptId id() {
        return new ExecutionAttemptId("run-1", "src", 0, 0);
    }

    @Override
    public void close() {
        executor.close();
        endpoint.close();
    }
}
