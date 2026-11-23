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
import org.devlive.pipeforge.engine.rpc.CheckpointFiles;
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
    private @Nullable Source<SourceSplit, ?> created;

    SourceOperator(
            PlannedNode node,
            OperatorCall call,
            JobContext job,
            ExecutionAttemptId task,
            int parallelism,
            JobMasterGateway master,
            PluginThreads threads) {
        this.node = node;
        this.call = call;
        this.context = new TaskReaderContext(
                job, task, parallelism, node.id(), call.classLoader(), master, threads.factory(call));
    }

    @SuppressWarnings("unchecked")
    /** Reads the positions a sink recorded for splits. */
    @FunctionalInterface
    interface Recorded {
        java.util.Map<String, String> of(java.util.Collection<String> splitIds);
    }

    /** Where to look up recorded positions when the task runs again; {@code null} otherwise. */
    private @org.jspecify.annotations.Nullable Recorded recorded;

    void open(
            JobContext job,
            ExpressionCompiler expressions,
            List<OperatorChain.Element> restored,
            @org.jspecify.annotations.Nullable Recorded recorded) {
        this.recorded = recorded;
        SourceFactory factory = (SourceFactory) node.component().factory();
        Source<SourceSplit, ?> source = (Source<SourceSplit, ?>) call.call(
                "create the source", () -> call.created(factory.createSource(NodeContext.of(node, job, expressions))));
        created = source;
        splitSerializer = call.call("get the split serializer", source::splitSerializer);
        SourceReader<SourceSplit> opened = call.call("create the reader", () -> source.createReader(context));
        reader = opened;
        if (!restored.isEmpty()) {
            // the splits the reader held at the checkpoint, each where it had got to
            VersionedSerializer<SourceSplit> serializer = splitSerializer;
            List<SourceSplit> splits = new ArrayList<>(restored.size());
            for (OperatorChain.Element element : restored) {
                splits.add(call.call(
                        "read a restored split", () -> serializer.deserialize(element.version(), element.bytes())));
            }
            List<SourceSplit> resumed = resumed(splits);
            call.run("add the restored splits", () -> opened.addSplits(resumed));
        }
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

    /**
     * Hands the row quota to a source that limits its own rate; {@code false} when the engine must
     * limit it.
     */
    boolean applyRateLimit(double rowsPerSecond) {
        if (created instanceof org.devlive.pipeforge.api.capability.SupportsRateLimit own) {
            call.run("apply the rate limit", () -> own.applyRateLimit(rowsPerSecond));
            return true;
        }
        return false;
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
                List<SourceSplit> resumed = resumed(splits);
                call.run("add splits", () -> current.addSplits(resumed));
            }
            case OperatorEvent.NoMoreSplits ignored -> call.run(
                    "end the split assignment", current::notifyNoMoreSplits);
            case OperatorEvent.RateLimit ignored -> {
                // the chain applies quotas
            }
        }
    }

    /**
     * The splits continued after the positions the sink recorded for them, when the source can
     * resume there: rows the sink committed after the last checkpoint are not read again.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<SourceSplit> resumed(List<SourceSplit> splits) {
        Recorded positions = recorded;
        if (positions == null
                || splits.isEmpty()
                || !(created instanceof org.devlive.pipeforge.api.capability.SupportsResume resuming)) {
            return splits;
        }
        List<String> ids = new ArrayList<>(splits.size());
        splits.forEach(split -> ids.add(split.splitId()));
        java.util.Map<String, String> found = positions.of(ids);
        List<SourceSplit> result = new ArrayList<>(splits.size());
        for (SourceSplit split : splits) {
            String position = found.get(split.splitId());
            result.add(
                    position == null
                            ? split
                            : call.call("resume split " + split.splitId(), () ->
                                    (SourceSplit) resuming.resume(split, position)));
        }
        return result;
    }

    /**
     * The reader's state for a checkpoint: its splits, each written with the split serializer, as
     * a list state (protocol specification, section 5.2.1).
     */
    StateUploader.Pending snapshot(long checkpointId, int subtask) {
        SourceReader<SourceSplit> current = opened();
        VersionedSerializer<SourceSplit> serializer = splitSerializer;
        if (serializer == null) {
            throw new IllegalStateException("The reader of " + node.id() + " is not open");
        }
        List<SourceSplit> splits = call.call("snapshot the reader", () -> current.snapshotState(checkpointId));
        List<byte[]> written = new ArrayList<>(splits.size());
        for (SourceSplit split : splits) {
            written.add(call.call("write split " + split.splitId(), () -> serializer.serialize(split)));
        }
        return new StateUploader.Pending(
                node.id(), "source", subtask, serializer.version(), CheckpointFiles.writeList(written));
    }

    /** Checkpoint {@code checkpointId} is complete: the reader may let its source know. */
    void notifyCheckpointComplete(long checkpointId) {
        SourceReader<SourceSplit> current = opened();
        call.run(
                "notify the reader of checkpoint " + checkpointId,
                () -> current.notifyCheckpointComplete(checkpointId));
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
            JobMasterGateway master,
            java.util.concurrent.ThreadFactory threadFactory)
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

        @Override
        public void splitFinished(String splitId) {
            master.sendReaderMessage(new ReaderMessage.SplitsFinished(task, node, java.util.List.of(splitId)));
        }
    }
}
