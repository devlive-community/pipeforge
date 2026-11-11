/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.List;
import java.util.Optional;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.core.plan.PlannedNode;
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
    private @Nullable Sink<Object, Object> sink;
    private @Nullable SinkWriter<Object, Object> writer;
    private @Nullable AsyncWriteBuffer buffer;

    SinkOperator(PlannedNode node, OperatorCall call, String taskName) {
        this.node = node;
        this.call = call;
        this.taskName = taskName;
    }

    @SuppressWarnings("unchecked")
    void open(JobContext job, int subtask, int parallelism, TaskSettings settings) {
        SinkFactory factory = (SinkFactory) node.component().factory();
        Sink<Object, Object> created = (Sink<Object, Object>)
                call.call("create the sink", () -> factory.createSink(NodeContext.of(node, job)));
        sink = created;
        WriterContext context = new TaskWriterContext(job, subtask, parallelism, call.classLoader());
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
                    call.classLoader());
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

    private void accept(Object item) {
        AsyncWriteBuffer async = buffer;
        if (async != null) {
            async.add(item);
        } else {
            writeNow(opened(), item);
        }
    }

    private void writeNow(SinkWriter<Object, Object> target, Object item) {
        if (item instanceof Row row) {
            call.run("write a row", () -> target.write(row));
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
            buffer = null;
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

    private record TaskWriterContext(JobContext job, int subtaskIndex, int parallelism, ClassLoader classLoader)
            implements WriterContext {}
}
