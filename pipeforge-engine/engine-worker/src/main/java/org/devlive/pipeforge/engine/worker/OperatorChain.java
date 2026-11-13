/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.expression.EngineExpressions;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.OperatorSnapshot;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.jspecify.annotations.Nullable;

/**
 * The operators of one task, connected by direct method calls: the reader emits into the first
 * transform, each transform into the next, the last into the sink. A row goes through the whole
 * chain before the reader emits the next one, on the task thread.
 */
final class OperatorChain {

    private final TaskDeployment deployment;
    private final SourceOperator source;
    private final List<TransformOperator> transforms;
    private final SinkOperator sink;
    private final ReaderOutput output;
    private final OperatorMetrics sourceMetrics;
    private final OperatorClock clock;
    private final ExpressionCompiler expressions;

    OperatorChain(TaskDeployment deployment, ErrorScope scope, String taskName, JobMasterGateway master) {
        this.deployment = deployment;
        ChainDescriptor chain = deployment.chain();
        JobContext job = chain.job();
        expressions = EngineExpressions.forRun(job.timeZone(), deployment.runStartTime());
        int sinkSlot = chain.nodes().size() - 1;
        // one slot per operator, from the source (0) to the sink, plus one for handing rows to the write thread
        clock = new OperatorClock(sinkSlot + 2);
        PlannedNode sinkNode = chain.sink();
        sink = new SinkOperator(sinkNode, call(sinkNode, scope), taskName, clock, sinkSlot, sinkSlot + 1);
        ChainOutput next = sink;
        List<TransformOperator> reversed = new ArrayList<>();
        List<PlannedNode> middle = chain.transforms();
        for (int i = middle.size() - 1; i >= 0; i--) {
            PlannedNode node = middle.get(i);
            TransformOperator transform = new TransformOperator(node, call(node, scope), next, clock, i + 1);
            reversed.add(transform);
            next = transform;
        }
        Collections.reverse(reversed);
        transforms = List.copyOf(reversed);
        PlannedNode sourceNode = chain.source();
        source = new SourceOperator(
                sourceNode, call(sourceNode, scope), job, deployment.id(), deployment.parallelism(), master);
        sourceMetrics = new OperatorMetrics(sourceNode.id());
        output = new ChainReaderOutput(next, sourceMetrics);
    }

    private static OperatorCall call(PlannedNode node, ErrorScope scope) {
        return new OperatorCall(node, node.component().classLoader(), scope);
    }

    /** Opens the operators from the sink up to the source, so that nothing emits into an operator that is not open. */
    void open() {
        JobContext job = deployment.chain().job();
        int subtask = deployment.id().subtask();
        int parallelism = deployment.parallelism();
        sink.open(job, subtask, parallelism, deployment.settings(), expressions);
        for (int i = transforms.size() - 1; i >= 0; i--) {
            transforms.get(i).open(job, subtask, parallelism, expressions);
        }
        source.open(job, expressions);
    }

    /** Starts the reader; see {@link SourceOperator#start()}. */
    void startSource() {
        source.start();
    }

    PollStatus pollNext() {
        boolean measured = clock.beginRead(0);
        try {
            return source.pollNext(output);
        } finally {
            if (measured) {
                clock.endRead();
            }
        }
    }

    /**
     * The counters and self time of every operator, from the source to the sink; callable from any
     * thread.
     *
     * <p>The task thread keeps moving rows while another thread reads the counters, so the counters
     * are read from the sink back to the source: every row a downstream counter has seen was counted
     * upstream before, and rows read but not yet accepted by the sink ({@code rowsRead + rowsExpanded
     * - rowsFiltered - accepted}) are never negative.
     */
    List<OperatorSnapshot> snapshot() {
        OperatorSnapshot[] operators = new OperatorSnapshot[transforms.size() + 2];
        operators[operators.length - 1] = sink.metrics().snapshot(sink.selfNanos());
        for (int i = transforms.size() - 1; i >= 0; i--) {
            TransformOperator transform = transforms.get(i);
            operators[i + 1] = transform.metrics().snapshot(transform.selfNanos());
        }
        operators[0] = sourceMetrics.snapshot(clock.selfNanos(0));
        return List.of(operators);
    }

    void handle(OperatorEvent event) {
        source.handle(event);
    }

    /** Ends the input and returns the sink's committable. */
    Optional<SerializedValue> finish(long checkpointId) {
        return sink.finish(checkpointId);
    }

    /** Interrupts blocking reads and writes; called from another thread. */
    void cancel() {
        source.cancel();
        sink.cancel();
    }

    /**
     * Closes the operators from the source down to the sink. Every operator is closed even when one
     * fails; the first failure is thrown afterwards.
     */
    void close() {
        @Nullable PipeForgeException first = null;
        List<Runnable> steps = new ArrayList<>();
        steps.add(source::close);
        transforms.forEach(t -> steps.add(t::close));
        steps.add(sink::close);
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

    long producerWaitNanos() {
        return sink.producerWaitNanos();
    }

    long writerWaitNanos() {
        return sink.writerWaitNanos();
    }

    /** Where the reader emits: straight into the first operator after it. */
    private static final class ChainReaderOutput implements ReaderOutput {
        private final ChainOutput next;
        private final OperatorMetrics metrics;

        ChainReaderOutput(ChainOutput next, OperatorMetrics metrics) {
            this.next = next;
            this.metrics = metrics;
        }

        @Override
        public void collect(Row row) {
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
