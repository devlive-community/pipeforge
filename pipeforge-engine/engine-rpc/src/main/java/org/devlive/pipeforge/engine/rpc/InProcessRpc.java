/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The in-process implementation of the RPC interfaces, used by the local mode. Each endpoint runs
 * the calls it receives on its own thread, one at a time and in the order they were made, as a
 * remote endpoint would. The caller never runs the callee's code, so a task thread asking for a
 * split never runs the enumerator, and the threading is the same as on a cluster.
 */
public final class InProcessRpc {

    private InProcessRpc() {}

    /** Serves {@code target} on its own thread named {@code rpc-<name>}. */
    public static Endpoint<TaskExecutorGateway> serve(String name, TaskExecutorGateway target) {
        Dispatcher dispatcher = new Dispatcher(name);
        TaskExecutorGateway proxy = new TaskExecutorGateway() {
            @Override
            public CompletableFuture<Void> deployTask(TaskDeployment deployment) {
                return dispatcher.dispatch(() -> target.deployTask(deployment));
            }

            @Override
            public CompletableFuture<Void> cancelTask(ExecutionAttemptId task) {
                return dispatcher.dispatch(() -> target.cancelTask(task));
            }

            @Override
            public CompletableFuture<Void> sendOperatorEvent(ExecutionAttemptId task, OperatorEvent event) {
                return dispatcher.dispatch(() -> target.sendOperatorEvent(task, event));
            }
        };
        return new Endpoint<>(proxy, dispatcher);
    }

    /** Serves {@code target} on its own thread named {@code rpc-<name>}. */
    public static Endpoint<JobMasterGateway> serve(String name, JobMasterGateway target) {
        Dispatcher dispatcher = new Dispatcher(name);
        JobMasterGateway proxy = new JobMasterGateway() {
            @Override
            public CompletableFuture<Void> updateTaskState(TaskStateUpdate update) {
                return dispatcher.dispatch(() -> target.updateTaskState(update));
            }

            @Override
            public CompletableFuture<Void> sendReaderMessage(ReaderMessage message) {
                return dispatcher.dispatch(() -> target.sendReaderMessage(message));
            }

            @Override
            public CompletableFuture<Void> reportMetrics(ExecutionAttemptId task, TaskMetricsSnapshot metrics) {
                return dispatcher.dispatch(() -> target.reportMetrics(task, metrics));
            }
        };
        return new Endpoint<>(proxy, dispatcher);
    }

    /**
     * A served gateway. Closing it stops its thread; later calls complete exceptionally.
     *
     * @param <G> the gateway interface
     */
    public static final class Endpoint<G> implements AutoCloseable {
        private final G gateway;
        private final Dispatcher dispatcher;

        Endpoint(G gateway, Dispatcher dispatcher) {
            this.gateway = gateway;
            this.dispatcher = dispatcher;
        }

        /** The gateway callers use. */
        public G gateway() {
            return gateway;
        }

        @Override
        public void close() {
            dispatcher.close();
        }
    }

    interface Call {
        CompletableFuture<Void> run();
    }

    static final class Dispatcher {
        private final ExecutorService executor;

        Dispatcher(String name) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rpc-" + name);
                thread.setDaemon(true);
                return thread;
            });
        }

        CompletableFuture<Void> dispatch(Call call) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                executor.execute(() -> {
                    try {
                        call.run().whenComplete((ignored, error) -> {
                            if (error != null) {
                                result.completeExceptionally(error);
                            } else {
                                result.complete(null);
                            }
                        });
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    }
                });
            } catch (RejectedExecutionException e) {
                result.completeExceptionally(new IllegalStateException("The endpoint is closed", e));
            }
            return result;
        }

        void close() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
