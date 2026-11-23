/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.rpc.CheckpointFiles;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.TaskSettings;
import org.jspecify.annotations.Nullable;

/**
 * The end of a chain: a sink writer, called directly on the task thread or, with the asynchronous
 * write buffer, on its own write thread.
 */
final class SinkOperator implements ChainOutput {

    private final PlannedNode node;
    private final OperatorCall call;
    private final String taskName;
    private final OperatorMetrics metrics;
    private final TaskMemory memory;
    private final PluginThreads threads;
    private final DirtyRows dirty;
    private final OperatorClock clock;
    private final int slot;
    private final int handoffSlot;
    private @Nullable Sink<Object, Object> sink;
    private @Nullable SinkWriter<Object, Object> writer;
    private @Nullable AsyncWriteBuffer buffer;
    private int subtask;

    /**
     * @param slot the clock slot of the writer on the task thread
     * @param handoffSlot the clock slot for handing rows to the write thread, which is not the
     *     writer's own time
     */
    SinkOperator(
            PlannedNode node,
            OperatorCall call,
            String taskName,
            OperatorClock clock,
            int slot,
            int handoffSlot,
            TaskMemory memory,
            PluginThreads threads,
            DirtyRows dirty) {
        this.dirty = dirty;
        this.memory = memory;
        this.threads = threads;
        this.node = node;
        this.call = call;
        this.taskName = taskName;
        this.clock = clock;
        this.slot = slot;
        this.handoffSlot = handoffSlot;
        this.metrics = new OperatorMetrics(node.id());
    }

    OperatorMetrics metrics() {
        return metrics;
    }

    /** Time in the writer: on the write thread with the buffer, sampled on the task thread without it. */
    long selfNanos() {
        AsyncWriteBuffer async = buffer;
        return async == null ? clock.selfNanos(slot) : async.busyNanos();
    }

    @SuppressWarnings("unchecked")
    void open(
            JobContext job,
            String jobId,
            int subtask,
            int parallelism,
            TaskSettings settings,
            ExpressionCompiler expressions) {
        this.subtask = subtask;
        SinkFactory factory = (SinkFactory) node.component().factory();
        Sink<Object, Object> created = (Sink<Object, Object>) call.call(
                "create the sink", () -> call.created(factory.createSink(NodeContext.of(node, job, expressions))));
        sink = created;
        WriterContext context = new TaskWriterContext(
                job,
                subtask,
                parallelism,
                call.classLoader(),
                threads.factory(call),
                this::reportBadRow,
                jobId,
                node.id(),
                0);
        SinkWriter<Object, Object> opened =
                call.call("create the writer", () -> created.createWriter(context, List.of()));
        writer = opened;
        if (settings.asyncWriteBuffer()) {
            buffer = new AsyncWriteBuffer(
                    "writer-" + taskName,
                    new AsyncWriteBuffer.Target() {
                        @Override
                        public void write(Object item) {
                            writeNow(opened, item);
                        }

                        @Override
                        public void flush() {
                            call.run("flush", opened::flush);
                        }
                    },
                    settings.writeBatchSize(),
                    settings.writeQueueCapacity(),
                    memory.limits().taskBytes(),
                    call.classLoader());
            AsyncWriteBuffer async = buffer;
            memory.writeBuffer(async::accountedBytes);
        }
    }

    @Override
    public void row(Row row) {
        accept(row);
    }

    @Override
    public void schemaChange(SchemaChangeEvent event) {
        accept(event);
    }

    /** The states taken at the last barrier, until the chain collects them. */
    private List<StateUploader.Pending> taken = List.of();

    /**
     * The end of the chain: every row before the barrier is written and flushed, then the writer
     * prepares the commit of the checkpoint and gives its state. The committable is committed once
     * the checkpoint is complete; the state lets the writer go on after a restore.
     */
    @Override
    public void barrier(org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier barrier) {
        long id = barrier.checkpointId();
        AsyncWriteBuffer async = buffer;
        if (async != null) {
            async.flush();
        }
        SinkWriter<Object, Object> current = opened();
        Sink<Object, Object> owner = sink;
        if (owner == null) {
            throw new IllegalStateException("The sink of " + node.id() + " is not open");
        }
        Optional<Object> committable =
                call.call("prepare the commit of checkpoint " + id, () -> current.prepareCommit(id));
        List<Object> state = call.call("snapshot the writer", () -> current.snapshotState(id));
        VersionedSerializer<Object> states = call.call("get the writer state serializer", owner::writerStateSerializer);
        VersionedSerializer<Object> committables =
                call.call("get the committable serializer", owner::committableSerializer);
        List<byte[]> written = new ArrayList<>();
        for (Object value : state == null ? List.of() : state) {
            written.add(call.call("write the writer state", () -> states.serialize(value)));
        }
        List<byte[]> prepared = new ArrayList<>();
        if (committable != null && committable.isPresent()) {
            Object value = committable.get();
            prepared.add(call.call("write the committable", () -> committables.serialize(value)));
        }
        taken = List.of(
                new StateUploader.Pending(
                        node.id(), "sink", subtask, states.version(), CheckpointFiles.writeList(written)),
                new StateUploader.Pending(
                        node.id(), "committer", subtask, committables.version(), CheckpointFiles.writeList(prepared)));
    }

    /** The states of the last barrier; empty until one reached the sink. */
    List<StateUploader.Pending> takeSnapshot() {
        List<StateUploader.Pending> states = taken;
        taken = List.of();
        return states;
    }

    private void accept(Object item) {
        if (item instanceof Row) {
            metrics.rowsIn.increment();
        }
        AsyncWriteBuffer async = buffer;
        int token = clock.enter(async != null ? handoffSlot : slot);
        try {
            if (async != null) {
                long bytes = 0;
                if (item instanceof Row row) {
                    // rows a transform made larger are checked here, before the buffer holds them
                    try {
                        bytes = memory.check(row, node.id());
                    } catch (PipeForgeException e) {
                        if (dirty.skipped(e, row, node.id(), metrics)) {
                            return;
                        }
                        throw e;
                    }
                }
                async.add(item, bytes);
            } else {
                writeNow(opened(), item);
            }
        } finally {
            clock.exit(token);
        }
    }

    private void writeNow(SinkWriter<Object, Object> target, Object item) {
        if (item instanceof Row row) {
            try {
                call.run("write a row", () -> target.write(row));
            } catch (PipeForgeException e) {
                if (dirty.skipped(e, row, node.id(), metrics)) {
                    return;
                }
                throw e;
            }
            metrics.rowsOut.increment();
        } else {
            call.run("apply a schema change", () -> target.applySchemaChange((SchemaChangeEvent) item));
        }
    }

    /**
     * Ends the input: waits until everything is written, then lets the writer prepare its commit
     * for {@code checkpointId}.
     *
     * @return the committable, written with the sink's serializer, if the sink commits in two phases
     */
    Optional<SerializedValue> finish(long checkpointId) {
        AsyncWriteBuffer async = buffer;
        if (async != null) {
            async.finish();
        }
        SinkWriter<Object, Object> current = opened();
        Optional<Object> committable = call.call("prepare the commit", () -> current.prepareCommit(checkpointId));
        if (committable == null || committable.isEmpty()) {
            return Optional.empty();
        }
        Sink<Object, Object> owner = sink;
        Object value = committable.get();
        return Optional.of(
                call.call("write the committable", () -> SerializedValue.of(owner.committableSerializer(), value)));
    }

    /** Interrupts the write thread; called from another thread when the task is cancelled. */
    void cancel() {
        AsyncWriteBuffer async = buffer;
        if (async != null) {
            async.interrupt();
        }
    }

    void close() {
        AsyncWriteBuffer async = buffer;
        if (async != null) {
            // kept for the final metrics; closing twice is harmless
            async.close();
        }
        SinkWriter<Object, Object> current = writer;
        if (current != null) {
            writer = null;
            call.run("close the writer", current::close);
        }
    }

    /** Time the task thread blocked on a full write queue; 0 without the buffer. */
    long producerWaitNanos() {
        AsyncWriteBuffer async = buffer;
        return async == null ? 0 : async.producerWaitNanos();
    }

    /** Batches in the write queue now; 0 without the buffer. */
    int queueDepth() {
        AsyncWriteBuffer async = buffer;
        return async == null ? 0 : async.depth();
    }

    /** The batches the write queue holds; 0 without the buffer. */
    int queueCapacity() {
        AsyncWriteBuffer async = buffer;
        return async == null ? 0 : async.capacity();
    }

    /** Time the write thread waited for rows; 0 without the buffer. */
    long writerWaitNanos() {
        AsyncWriteBuffer async = buffer;
        return async == null ? 0 : async.writerWaitNanos();
    }

    private SinkWriter<Object, Object> opened() {
        SinkWriter<Object, Object> current = writer;
        if (current == null) {
            throw new IllegalStateException("The writer of " + node.id() + " is not open");
        }
        return current;
    }

    /** A row the writer accepted earlier and the target refused later: counted as bad, not written. */
    private void reportBadRow(Row row, String reason) {
        PipeForgeException error = PipeForgeException.of(ErrorCode.BAD_ROW)
                .param("node", node.id())
                .param("reason", reason)
                .scope(org.devlive.pipeforge.common.error.ErrorScope.node(node.id()))
                .build();
        if (!dirty.skipped(error, row, node.id(), metrics)) {
            throw error;
        }
        metrics.rowsOut.decrement();
    }

    record TaskWriterContext(
            JobContext job,
            int subtaskIndex,
            int parallelism,
            ClassLoader classLoader,
            java.util.concurrent.ThreadFactory threadFactory,
            java.util.function.BiConsumer<Row, String> badRows,
            String jobId,
            String nodeId,
            long restoredCheckpointId)
            implements WriterContext {

        @Override
        public void reportBadRow(Row row, String reason) {
            badRows.accept(row, reason);
        }

        @Override
        public String transactionId(long checkpointId) {
            return jobId + "-" + nodeId + "-" + subtaskIndex + "-" + checkpointId;
        }
    }
}
