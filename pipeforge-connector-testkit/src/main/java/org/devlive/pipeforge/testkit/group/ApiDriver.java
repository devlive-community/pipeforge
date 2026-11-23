/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.group;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.GlobalCommitter;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkCommitter;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.ReaderEvent;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.source.SourceSplit;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.core.expression.EngineExpressions;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.jspecify.annotations.Nullable;
import org.opentest4j.AssertionFailedError;

/**
 * Drives a source's enumerator and readers, or a sink's writers and committers, through the
 * connector API alone, the way the engine does around a checkpoint: states go through the plugin's
 * serializers, a "crash" drops every instance, and a restore creates new ones from the states.
 */
final class ApiDriver {

    private ApiDriver() {}

    record NodeContext(
            String nodeName,
            ReadonlyConfig options,
            JobContext job,
            List<CatalogTable> inputTables,
            ClassLoader classLoader,
            ExpressionCompiler expressions)
            implements FactoryContext {}

    static FactoryContext context(JobPlan plan, String nodeId) {
        PlannedNode node = plan.node(nodeId).orElseThrow();
        return new NodeContext(
                node.id(),
                node.config(),
                new JobContext(plan.name(), plan.parallelism(), plan.timeZone()),
                node.inputTables(),
                node.component().classLoader(),
                EngineExpressions.forRun(plan.timeZone(), Instant.now()));
    }

    /** Writes a value and reads it back with the plugin's serializer, as a checkpoint and restore do. */
    static <T> T roundTrip(VersionedSerializer<T> serializer, T value, String what) {
        try {
            return serializer.deserialize(serializer.version(), serializer.serialize(value));
        } catch (IOException | RuntimeException e) {
            throw new AssertionFailedError("The " + what + " does not survive its serializer: " + e.getMessage(), e);
        }
    }

    /** A list of serialized values: the count, then each value's length and bytes. */
    static byte[] frame(List<byte[]> values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(values.size());
            for (byte[] value : values) {
                out.writeInt(value.length);
                out.write(value);
            }
        }
        return bytes.toByteArray();
    }

    static List<byte[]> unframe(byte[] framed) throws IOException {
        List<byte[]> values = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(framed))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                byte[] value = new byte[in.readInt()];
                in.readFully(value);
                values.add(value);
            }
        }
        return values;
    }

    // ------------------------------------------------------------------------------------ source

    /** The state of a source at a checkpoint: the enumerator's and each reader's splits. */
    record SourceState<S, E>(E enumerator, List<List<S>> readers) {}

    /** An enumerator and its readers; split requests and assignments go through a mailbox, as in the engine. */
    static final class SourceRun<S extends SourceSplit, E> implements AutoCloseable {
        final Source<S, E> source;
        final SplitEnumerator<S, E> enumerator;
        final List<SourceReader<S>> readers = new ArrayList<>();
        final boolean[] ended;
        final List<Row> rows = new ArrayList<>();
        private final Deque<Runnable> mail = new ArrayDeque<>();

        @SuppressWarnings("unchecked")
        SourceRun(SourceFactory factory, FactoryContext context, int parallelism, @Nullable SourceState<S, E> restored)
                throws Exception {
            this.source = (Source<S, E>) factory.createSource(context);
            this.ended = new boolean[parallelism];
            JobContext job = context.job();
            EnumeratorContext<S> enumeratorContext = new EnumeratorContext<>() {
                @Override
                public JobContext job() {
                    return job;
                }

                @Override
                public int parallelism() {
                    return parallelism;
                }

                @Override
                public Set<Integer> registeredReaders() {
                    Set<Integer> all = new LinkedHashSet<>();
                    for (int i = 0; i < readers.size(); i++) {
                        all.add(i);
                    }
                    return all;
                }

                @Override
                public void assignSplits(Map<Integer, List<S>> assignment) {
                    assignment.forEach((reader, splits) ->
                            mail.add(() -> readers.get(reader).addSplits(splits)));
                }

                @Override
                public void signalNoMoreSplits(int readerId) {
                    mail.add(() -> readers.get(readerId).notifyNoMoreSplits());
                }
            };
            this.enumerator = restored == null
                    ? source.createEnumerator(enumeratorContext)
                    : source.restoreEnumerator(enumeratorContext, restored.enumerator());
            enumerator.start();
            for (int i = 0; i < parallelism; i++) {
                int index = i;
                readers.add(source.createReader(new ReaderContext() {
                    @Override
                    public JobContext job() {
                        return job;
                    }

                    @Override
                    public int subtaskIndex() {
                        return index;
                    }

                    @Override
                    public int parallelism() {
                        return parallelism;
                    }

                    @Override
                    public void sendSplitRequest() {
                        mail.add(() -> enumerator.handleSplitRequest(index));
                    }

                    @Override
                    public void sendReaderEvent(ReaderEvent event) {
                        mail.add(() -> enumerator.handleReaderEvent(index, event));
                    }

                    @Override
                    public ClassLoader classLoader() {
                        return context.classLoader();
                    }
                }));
            }
            for (int i = 0; i < parallelism; i++) {
                if (restored != null && i < restored.readers().size()) {
                    readers.get(i).addSplits(restored.readers().get(i));
                }
                enumerator.addReader(i);
                readers.get(i).start();
            }
        }

        /** Reads until every reader ended or {@code rows} rows were read; false when it stalled. */
        boolean readUntil(int target) throws Exception {
            ReaderOutput output = new ReaderOutput() {
                @Override
                public void collect(Row row) {
                    rows.add(row);
                }

                @Override
                public void collect(SchemaChangeEvent event) {}
            };
            int idle = 0;
            while (rows.size() < target && !done()) {
                boolean progress = drainMail();
                for (int i = 0; i < readers.size() && rows.size() < target; i++) {
                    if (ended[i]) {
                        continue;
                    }
                    PollStatus status = readers.get(i).pollNext(output);
                    if (status == PollStatus.END_OF_INPUT) {
                        ended[i] = true;
                    }
                    progress |= status != PollStatus.NOTHING_AVAILABLE;
                }
                idle = progress ? 0 : idle + 1;
                if (idle > 10_000) {
                    return false;
                }
                if (idle > 100) {
                    Thread.sleep(1);
                }
            }
            return true;
        }

        private boolean done() {
            for (boolean end : ended) {
                if (!end) {
                    return false;
                }
            }
            return true;
        }

        private boolean drainMail() {
            boolean any = false;
            Runnable next;
            while ((next = mail.poll()) != null) {
                next.run();
                any = true;
            }
            return any;
        }

        /**
         * The checkpoint: every split assignment sent so far is delivered first (the engine's valve
         * holds the later ones), then the enumerator and the readers give their states, which go
         * through the serializers.
         */
        SourceState<S, E> snapshot(long checkpointId) throws Exception {
            drainMail();
            E state = roundTrip(
                    source.enumeratorStateSerializer(), enumerator.snapshotState(checkpointId), "enumerator state");
            List<List<S>> splits = new ArrayList<>();
            for (SourceReader<S> reader : readers) {
                List<S> own = new ArrayList<>();
                for (S split : reader.snapshotState(checkpointId)) {
                    own.add(roundTrip(source.splitSerializer(), split, "split " + split.splitId()));
                }
                splits.add(own);
            }
            return new SourceState<>(state, splits);
        }

        /** Drops everything, as a failure would. */
        @Override
        public void close() throws Exception {
            for (SourceReader<S> reader : readers) {
                reader.close();
            }
            enumerator.close();
        }
    }

    // -------------------------------------------------------------------------------------- sink

    record TestWriterContext(
            JobContext job,
            int subtaskIndex,
            int parallelism,
            ClassLoader classLoader,
            String nodeId,
            long restoredCheckpointId)
            implements WriterContext {

        @Override
        public String transactionId(long checkpointId) {
            return "testkit-" + nodeId + "-" + subtaskIndex + "-" + checkpointId;
        }
    }

    /** A sink with one writer, its committer and global committer. */
    static final class SinkRun<C, W> {
        final Sink<C, W> sink;
        final FactoryContext context;
        final Optional<SinkCommitter<C>> committer;
        final Optional<GlobalCommitter<C, ?>> global;

        @SuppressWarnings("unchecked")
        SinkRun(SinkFactory factory, FactoryContext context) throws Exception {
            this.sink = (Sink<C, W>) factory.createSink(context);
            this.context = context;
            this.committer = sink.createCommitter();
            this.global = sink.createGlobalCommitter();
        }

        SinkWriter<C, W> writer(long restoredCheckpointId, List<W> restored) throws Exception {
            return sink.createWriter(
                    new TestWriterContext(
                            context.job(), 0, 1, context.classLoader(), context.nodeName(), restoredCheckpointId),
                    restored);
        }

        /** Commits as the master does once a checkpoint is complete; retried committables are committed again. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void commit(List<C> committables) throws Exception {
            if (committables.isEmpty()) {
                return;
            }
            if (committer.isPresent()) {
                List<C> pending = committables;
                for (int attempt = 0; !pending.isEmpty(); attempt++) {
                    if (attempt == 5) {
                        throw new AssertionFailedError("The committer still retries " + pending + " after 5 attempts");
                    }
                    pending = committer.get().commit(pending);
                }
            }
            if (global.isPresent()) {
                GlobalCommitter raw = global.get();
                raw.commit(List.of(raw.combine(committables)));
            }
        }

        /** Prepares the commit of a checkpoint and returns the committable, through its serializer. */
        List<C> prepare(SinkWriter<C, W> writer, long checkpointId) throws Exception {
            Optional<C> committable = writer.prepareCommit(checkpointId);
            return committable == null || committable.isEmpty()
                    ? List.of()
                    : List.of(roundTrip(sink.committableSerializer(), committable.get(), "committable"));
        }

        List<W> snapshot(SinkWriter<C, W> writer, long checkpointId) throws Exception {
            List<W> state = new ArrayList<>();
            for (W value : writer.snapshotState(checkpointId)) {
                state.add(roundTrip(sink.writerStateSerializer(), value, "writer state"));
            }
            return state;
        }
    }
}
