/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.expression.EngineExpressions;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.OperatorSnapshot;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.jspecify.annotations.Nullable;

/**
 * The operators of one task, connected by direct method calls. Rows enter from the reader of the
 * source or from the task's network input, go through each transform, and leave into the sink or
 * the task's network outputs; a row goes through the whole chain before the next one enters, on
 * the task thread.
 *
 * <p>The operator clock has a slot for the reader or the input (0), one per node, and one for the
 * network outputs or the hand-off to the sink's write thread.
 */
final class OperatorChain {

    private final TaskDeployment deployment;
    private final ChainDescriptor chain;
    private final @Nullable SourceOperator source;
    private final List<TransformOperator> transforms;
    private final @Nullable SinkOperator sink;
    private final @Nullable ReaderOutput output;
    private final @Nullable OperatorMetrics sourceMetrics;
    private final OperatorClock clock;
    private final TaskMemory memory;
    private final DirtyRows dirty;
    private final Throttle throttle = new Throttle();
    private final ExpressionCompiler expressions;
    private final @Nullable NetworkEnvironment network;
    private final BooleanSupplier cancelled;
    /** Where rows enter the first operator after the reader or the input. */
    private final ChainOutput first;

    private final @Nullable DeferredOutput deferred;
    // set on the task thread when the chain opens, read by the metrics reporter
    private volatile @Nullable NetworkOutput outputs;
    private volatile @Nullable NetworkInput input;

    OperatorChain(
            TaskDeployment deployment,
            ErrorScope scope,
            String taskName,
            JobMasterGateway master,
            @Nullable NetworkEnvironment network,
            BooleanSupplier cancelled,
            TaskMemory memory,
            PluginThreads threads,
            DirtyRows dirty) {
        this.dirty = dirty;
        this.memory = memory;
        this.deployment = deployment;
        this.chain = deployment.chain();
        this.network = network;
        this.cancelled = cancelled;
        JobContext job = chain.job();
        expressions = EngineExpressions.forRun(job.timeZone(), deployment.runStartTime());
        List<PlannedNode> nodes = chain.nodes();
        int offset = chain.hasSource() ? 0 : 1;
        int last = nodes.size() - 1 + offset;
        clock = new OperatorClock(last + 2);
        if ((!chain.hasSource() || !chain.hasSink()) && network == null) {
            throw PipeForgeException.of(ErrorCode.TOPOLOGY_UNSUPPORTED)
                    .param(
                            "node",
                            chain.hasSource() ? chain.tail().id() : chain.head().id())
                    .param("reason", "its task exchanges rows with other tasks, but this worker has no data exchange")
                    .scope(scope)
                    .build();
        }

        ChainOutput next;
        if (chain.hasSink()) {
            PlannedNode node = chain.sink();
            sink = new SinkOperator(node, call(node, scope), taskName, clock, last, last + 1, memory, threads, dirty);
            deferred = null;
            next = sink;
        } else {
            sink = null;
            deferred = new DeferredOutput();
            next = deferred;
        }
        List<TransformOperator> reversed = new ArrayList<>();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            PlannedNode node = nodes.get(i);
            if (node.kind() == NodeKind.TRANSFORM) {
                TransformOperator transform =
                        new TransformOperator(node, call(node, scope), next, clock, i + offset, dirty);
                reversed.add(transform);
                next = transform;
            }
        }
        transforms = List.copyOf(reversed.reversed());
        first = next;
        ChainOutput head = next;
        aligned = (barrier, nanos) -> head.barrier(barrier);
        if (chain.hasSource()) {
            PlannedNode node = chain.source();
            source = new SourceOperator(
                    node, call(node, scope), job, deployment.id(), deployment.parallelism(), master, threads);
            sourceMetrics = new OperatorMetrics(node.id());
            output = new ChainReaderOutput(next, sourceMetrics, memory, node.id(), dirty, throttle);
        } else {
            source = null;
            sourceMetrics = null;
            output = null;
        }
    }

    /** Reads the elements of a state this task restores. */
    @FunctionalInterface
    interface StateReader {
        List<byte[]> read(org.devlive.pipeforge.engine.rpc.StateHandle handle);
    }

    /** A state element with the serializer version that wrote it. */
    record Element(int version, byte[] bytes) {}

    /**
     * The elements of the {@code kind} states of {@code node} the task restores, in the order of
     * the handles; empty for a fresh start.
     */
    private static List<Element> restored(
            TaskDeployment.Restore restore, String node, String kind, StateReader states) {
        List<Element> elements = new ArrayList<>();
        for (org.devlive.pipeforge.engine.rpc.StateHandle handle : restore.of(node)) {
            if (handle.operatorKind().equals(kind)) {
                for (byte[] bytes : states.read(handle)) {
                    elements.add(new Element(handle.serializerVersion(), bytes));
                }
            }
        }
        return elements;
    }

    private static OperatorCall call(PlannedNode node, ErrorScope scope) {
        return new OperatorCall(node, node.component().classLoader(), scope);
    }

    /**
     * Opens the outputs and operators from the end of the chain to its start, so that nothing
     * emits into something that is not open, and asks the producers of the input for their data
     * last.
     */
    void open(StateReader states) {
        JobContext job = chain.job();
        TaskDeployment.Restore restore = deployment.restore();
        int subtask = deployment.id().subtask();
        int parallelism = deployment.parallelism();
        if (!chain.hasSink()) {
            int slot = chain.nodes().size() + (chain.hasSource() ? 0 : 1);
            NetworkOutput created = new NetworkOutput(
                    network(), deployment.id(), chain, chain.tail().outputTables(), cancelled, clock, slot);
            outputs = created;
            if (deferred != null) {
                deferred.target = created;
            }
        }
        if (sink != null) {
            sink.open(
                    job,
                    deployment.run().jobId(),
                    subtask,
                    parallelism,
                    deployment.settings(),
                    expressions,
                    restore.checkpointId(),
                    restored(restore, chain.tail().id(), "sink", states));
        }
        for (int i = transforms.size() - 1; i >= 0; i--) {
            transforms.get(i).open(job, subtask, parallelism, expressions);
        }
        if (source != null) {
            source.open(job, expressions, restored(restore, chain.head().id(), "source", states));
        } else {
            input = new NetworkInput(network(), deployment, chain.head().inputTables());
        }
        memory.network(() -> {
            NetworkOutput out = outputs;
            NetworkInput in = input;
            return (out == null ? 0 : out.memoryBytes()) + (in == null ? 0 : in.memoryBytes());
        });
    }

    private NetworkEnvironment network() {
        if (network == null) {
            throw new IllegalStateException("no data exchange");
        }
        return network;
    }

    /** Starts the reader; see {@link SourceOperator#start()}. */
    void startSource() {
        if (source != null) {
            source.start();
        }
    }

    // ------------------------------------------------------------------ checkpoints

    /** Takes the checkpoint of an aligned barrier; the first operator receives the barrier by default. */
    private NetworkInput.Aligned aligned;

    /** Who takes the checkpoint once a barrier arrived on every input channel; set before polling. */
    void onAligned(NetworkInput.Aligned handler) {
        this.aligned = handler;
    }

    boolean hasSource() {
        return source != null;
    }

    /**
     * The states of the chain for a checkpoint, taken on the task thread before the barrier moves
     * on: the reader's splits. Transforms keep no state; the sink's comes with sink state.
     */
    List<StateUploader.Pending> snapshot(long checkpointId) {
        List<StateUploader.Pending> states = new ArrayList<>();
        if (source != null) {
            states.add(source.snapshot(checkpointId, deployment.id().subtask()));
        }
        return states;
    }

    /** Sends the barrier down the chain, after every row read before it. */
    void barrier(org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier barrier) {
        first.barrier(barrier);
    }

    /** The sink's states of the barrier that just passed: its writer's state and committable. */
    List<StateUploader.Pending> sinkSnapshot() {
        return sink == null ? List.of() : sink.takeSnapshot();
    }

    void notifyCheckpointComplete(long checkpointId) {
        if (sink != null) {
            sink.notifyCheckpointComplete(checkpointId);
        }
        if (source != null) {
            source.notifyCheckpointComplete(checkpointId);
        }
    }

    void abortCheckpoint(long checkpointId) {
        NetworkInput current = input;
        if (current != null) {
            current.abort(checkpointId);
        }
    }

    /** Whether a poll waits for data itself, so the task need not wait for mail in between. */
    boolean pollWaits() {
        return source == null;
    }

    PollStatus pollNext() throws InterruptedException {
        boolean measured = clock.beginRead(0);
        try {
            if (source != null && output != null) {
                try {
                    return source.pollNext(output);
                } catch (PipeForgeException e) {
                    // a record the reader could not read; errors of nodes after it name their node
                    OperatorMetrics metrics = sourceMetrics;
                    String node = chain.source().id();
                    if (metrics != null && node.equals(e.scope().node())) {
                        metrics.rowsIn.increment();
                        if (dirty.skipped(e, null, node, metrics)) {
                            return PollStatus.MORE_AVAILABLE;
                        }
                        metrics.rowsIn.decrement();
                    }
                    throw e;
                }
            }
            NetworkInput current = input;
            if (current == null) {
                throw new IllegalStateException("the input of " + deployment.id() + " is not open");
            }
            return current.poll(first, aligned);
        } finally {
            if (measured) {
                clock.endRead();
            }
        }
    }

    /**
     * The counters and self time of every node in chain order; callable from any thread.
     *
     * <p>The task thread keeps moving rows while another thread reads the counters, so the counters
     * are read from the end of the chain back to its start: every row a downstream counter has seen
     * was counted upstream before, and rows read but not yet accepted by the sink ({@code rowsRead +
     * rowsExpanded - rowsFiltered - accepted}) are never negative.
     */
    List<OperatorSnapshot> snapshot() {
        List<PlannedNode> nodes = chain.nodes();
        OperatorSnapshot[] operators = new OperatorSnapshot[nodes.size()];
        int index = nodes.size() - 1;
        if (sink != null) {
            operators[index--] = sink.metrics().snapshot(sink.selfNanos());
        }
        for (int i = transforms.size() - 1; i >= 0; i--) {
            TransformOperator transform = transforms.get(i);
            operators[index--] = transform.metrics().snapshot(transform.selfNanos());
        }
        if (sourceMetrics != null) {
            operators[index] = sourceMetrics.snapshot(clock.selfNanos(0));
        }
        return List.of(operators);
    }

    void handle(OperatorEvent event) {
        SourceOperator current = source;
        if (current == null) {
            return;
        }
        if (event instanceof OperatorEvent.RateLimit limit) {
            // a source that limits its own rate takes the rows; bytes are always the engine's
            boolean own = current.applyRateLimit(limit.rowsPerSecond());
            throttle.quota(own ? 0 : limit.rowsPerSecond(), limit.bytesPerSecond(), System.nanoTime());
            return;
        }
        current.handle(event);
    }

    /** How long the task waits for its rate limit quota before it reads again; 0 to read now. */
    long throttleNanos() {
        return source == null ? 0 : throttle.waitNanos(System.nanoTime());
    }

    /** Ends the input: returns the sink's committables, or ends the outputs. */
    List<SerializedValue> finish(long checkpointId) {
        if (sink != null) {
            return sink.finish(checkpointId);
        }
        NetworkOutput current = outputs;
        if (current != null) {
            current.finish();
        }
        return List.of();
    }

    /** Interrupts blocking reads and writes; called from another thread. */
    void cancel() {
        if (source != null) {
            source.cancel();
        }
        if (sink != null) {
            sink.cancel();
        }
    }

    /**
     * Closes the operators from the start of the chain to its end, then the input and outputs.
     * Every one is closed even when one fails; the first failure is thrown afterwards.
     */
    void close() {
        @Nullable PipeForgeException first = null;
        List<Runnable> steps = new ArrayList<>();
        if (source != null) {
            steps.add(source::close);
        }
        transforms.forEach(t -> steps.add(t::close));
        if (sink != null) {
            steps.add(sink::close);
        }
        steps.add(() -> {
            NetworkInput current = input;
            if (current != null) {
                current.close();
            }
        });
        steps.add(() -> {
            NetworkOutput current = outputs;
            if (current != null) {
                current.release();
            }
        });
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (PipeForgeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** Time the reader waited for the write queue of the sink. */
    long producerWaitNanos() {
        return sink != null ? sink.producerWaitNanos() : 0;
    }

    /** Time the task waited for network buffers of its outputs. */
    long backpressureNanos() {
        NetworkOutput current = outputs;
        return current == null ? 0 : current.backpressureNanos();
    }

    long bytesSent() {
        NetworkOutput current = outputs;
        return current == null ? 0 : current.bytesSent();
    }

    long bytesReceived() {
        NetworkInput current = input;
        return current == null ? 0 : current.bytesReceived();
    }

    int queueDepth() {
        return sink != null ? sink.queueDepth() : 0;
    }

    int queueCapacity() {
        return sink != null ? sink.queueCapacity() : 0;
    }

    long writerWaitNanos() {
        return sink != null ? sink.writerWaitNanos() : 0;
    }

    /** The network outputs, which are created when the chain opens. */
    private static final class DeferredOutput implements ChainOutput {
        private @Nullable NetworkOutput target;

        @Override
        public void row(Row row) {
            NetworkOutput current = target;
            if (current == null) {
                throw new IllegalStateException("the outputs are not open");
            }
            current.row(row);
        }

        @Override
        public void schemaChange(SchemaChangeEvent event) {
            NetworkOutput current = target;
            if (current == null) {
                throw new IllegalStateException("the outputs are not open");
            }
            current.schemaChange(event);
        }

        @Override
        public void barrier(org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier barrier) {
            NetworkOutput current = target;
            if (current == null) {
                throw new IllegalStateException("the outputs are not open");
            }
            current.barrier(barrier);
        }
    }

    /** Where the reader emits: straight into the first operator after it. */
    private static final class ChainReaderOutput implements ReaderOutput {
        private final ChainOutput next;
        private final OperatorMetrics metrics;
        private final TaskMemory memory;
        private final String node;
        private final DirtyRows dirty;
        private final Throttle throttle;

        ChainReaderOutput(
                ChainOutput next,
                OperatorMetrics metrics,
                TaskMemory memory,
                String node,
                DirtyRows dirty,
                Throttle throttle) {
            this.next = next;
            this.metrics = metrics;
            this.memory = memory;
            this.node = node;
            this.dirty = dirty;
            this.throttle = throttle;
        }

        @Override
        public void collect(Row row) {
            try {
                throttle.took(memory.check(row, node));
            } catch (PipeForgeException e) {
                // read, but too large to pass on
                metrics.rowsIn.increment();
                if (dirty.skipped(e, row, node, metrics)) {
                    return;
                }
                throw e;
            }
            metrics.rowsIn.increment();
            metrics.rowsOut.increment();
            next.row(row);
        }

        @Override
        public void collect(SchemaChangeEvent event) {
            next.schemaChange(event);
        }
    }
}
