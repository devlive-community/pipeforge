/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.ReaderEvent;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.source.SourceSplit;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.ReaderMessage;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.jspecify.annotations.Nullable;

/** The head of a chain: a source reader that pulls splits from the enumerator in the job master. */
final class SourceOperator {

    private final PlannedNode node;
    private final OperatorCall call;
    private final ReaderContext context;
    private @Nullable SourceReader<SourceSplit> reader;
    private @Nullable VersionedSerializer<SourceSplit> splitSerializer;

    SourceOperator(
            PlannedNode node,
            OperatorCall call,
            JobContext job,
            ExecutionAttemptId task,
            int parallelism,
            JobMasterGateway master) {
        this.node = node;
        this.call = call;
        this.context = new TaskReaderContext(job, task, parallelism, node.id(), call.classLoader(), master);
    }

    @SuppressWarnings("unchecked")
    void open(JobContext job, ExpressionCompiler expressions) {
        SourceFactory factory = (SourceFactory) node.component().factory();
        Source<SourceSplit, ?> source = (Source<SourceSplit, ?>)
                call.call("create the source", () -> factory.createSource(NodeContext.of(node, job, expressions)));
        splitSerializer = call.call("get the split serializer", source::splitSerializer);
        reader = call.call("create the reader", () -> source.createReader(context));
    }

    /**
     * Starts the reader. Called after the task reported RUNNING, so that the job master has
     * registered the reader before its first split request arrives.
     */
    void start() {
        SourceReader<SourceSplit> current = opened();
        call.run("start the reader", current::start);
    }

    PollStatus pollNext(ReaderOutput output) {
        SourceReader<SourceSplit> current = opened();
        return call.call("read", () -> current.pollNext(output));
    }

    void handle(OperatorEvent event) {
        SourceReader<SourceSplit> current = opened();
        switch (event) {
            case OperatorEvent.AddSplits add -> {
                VersionedSerializer<SourceSplit> serializer = splitSerializer;
                List<SourceSplit> splits = new ArrayList<>(add.splits().size());
                for (SerializedValue value : add.splits()) {
                    splits.add(call.call("read an assigned split", () -> value.deserialize(serializer)));
                }
                call.run("add splits", () -> current.addSplits(splits));
            }
            case OperatorEvent.NoMoreSplits ignored -> call.run(
                    "end the split assignment", current::notifyNoMoreSplits);
        }
    }

    /** Interrupts a blocking read; called from another thread. Failures are ignored: the task is stopping. */
    void cancel() {
        SourceReader<SourceSplit> current = reader;
        if (current != null) {
            try {
                call.run("cancel the reader", current::cancel);
            } catch (RuntimeException e) {
                // the task is being cancelled; the interrupt that follows is the fallback
            }
        }
    }

    void close() {
        SourceReader<SourceSplit> current = reader;
        if (current != null) {
            reader = null;
            call.run("close the reader", current::close);
        }
    }

    private SourceReader<SourceSplit> opened() {
        SourceReader<SourceSplit> current = reader;
        if (current == null) {
            throw new IllegalStateException("The reader of " + node.id() + " is not open");
        }
        return current;
    }

    private record TaskReaderContext(
            JobContext job,
            ExecutionAttemptId task,
            int parallelism,
            String node,
            ClassLoader classLoader,
            JobMasterGateway master)
            implements ReaderContext {

        @Override
        public int subtaskIndex() {
            return task.subtask();
        }

        @Override
        public void sendSplitRequest() {
            master.sendReaderMessage(new ReaderMessage.SplitRequest(task, node));
        }

        @Override
        public void sendReaderEvent(ReaderEvent event) {
            master.sendReaderMessage(new ReaderMessage.Custom(task, node, event));
        }
    }
}
