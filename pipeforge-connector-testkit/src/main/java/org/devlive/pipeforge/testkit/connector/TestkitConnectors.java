/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.connector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.serialization.StringSerializer;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkContext;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceContext;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.source.SourceSplit;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.AddColumn;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.jspecify.annotations.Nullable;

/**
 * The connectors the testkit pairs with the connector under test, both on registered {@link
 * Datasets}:
 *
 * <ul>
 *   <li>source {@value #ROWS}: reads the rows of one or more data sets (one table each), cut into
 *       {@code splits} splits that readers pull; with {@code schema-change} it also adds a column
 *       {@value #ADDED_COLUMN} to every table once the rows of the first split are read;
 *   <li>sink {@value #COLLECT}: appends what it receives to a data set; rows become visible when
 *       the writer flushes.
 * </ul>
 */
public final class TestkitConnectors {

    public static final String ROWS = "testkit-rows";
    public static final String COLLECT = "testkit-collect";

    /** The column the source adds with {@code schema-change: true}. */
    public static final String ADDED_COLUMN = "testkit_added";

    public static final Option<String> DATASET = Options.key("dataset")
            .stringType()
            .noDefault()
            .withDescription("The testkit data set id; the source reads several, one table each, separated by commas.");
    public static final Option<Boolean> SCHEMA_CHANGE = Options.key("schema-change")
            .booleanType()
            .defaultValue(false)
            .withDescription("Add the column " + ADDED_COLUMN + " to every table after the first split.");
    public static final Option<Integer> SPLITS = Options.key("splits")
            .intType()
            .defaultValue(4)
            .withRange(1, 10_000)
            .withDescription("How many splits the rows are cut into.");

    private TestkitConnectors() {}

    public static SourceFactory rows() {
        return new RowsFactory();
    }

    public static SinkFactory collect() {
        return new CollectFactory();
    }

    // ------------------------------------------------------------------ source

    /** Rows {@code [from, to)} of a data set. */
    record IndexSplit(String splitId, int from, int to) implements SourceSplit {}

    static final class IndexSplitSerializer implements VersionedSerializer<IndexSplit> {
        @Override
        public int version() {
            return 1;
        }

        @Override
        public byte[] serialize(IndexSplit value) {
            byte[] id = value.splitId().getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(12 + id.length)
                    .putInt(value.from())
                    .putInt(value.to())
                    .putInt(id.length)
                    .put(id)
                    .array();
        }

        @Override
        public IndexSplit deserialize(int version, byte[] bytes) throws IOException {
            if (version != 1) {
                throw new IOException("Unknown split version " + version);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int from = buffer.getInt();
            int to = buffer.getInt();
            byte[] id = new byte[buffer.getInt()];
            buffer.get(id);
            return new IndexSplit(new String(id, StandardCharsets.UTF_8), from, to);
        }
    }

    private static final class RowsFactory implements SourceFactory {
        @Override
        public String identifier() {
            return ROWS;
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder()
                    .required(DATASET)
                    .optional(SPLITS, SCHEMA_CHANGE)
                    .build();
        }

        @Override
        public Source<?, ?> createSource(FactoryContext context) {
            ReadonlyConfig options = context.options();
            return new RowsSource(options.get(DATASET), options.get(SPLITS), options.get(SCHEMA_CHANGE));
        }
    }

    private static final class RowsSource implements Source<IndexSplit, String> {
        private static final long serialVersionUID = 1L;
        private final String dataset;
        private final int splits;
        private final boolean schemaChange;

        RowsSource(String dataset, int splits, boolean schemaChange) {
            this.dataset = dataset;
            this.splits = splits;
            this.schemaChange = schemaChange;
        }

        /** The data sets read, in order. */
        private List<TestTable> tables() {
            return Arrays.stream(dataset.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .map(Datasets::table)
                    .toList();
        }

        /** The rows of every data set, one after the other. */
        private List<Row> rows() {
            return tables().stream().flatMap(t -> t.rows().stream()).toList();
        }

        @Override
        public Boundedness boundedness() {
            return Boundedness.BOUNDED;
        }

        @Override
        public List<CatalogTable> producedTables(SourceContext context) {
            return tables().stream().map(TestTable::table).toList();
        }

        @Override
        public SplitEnumerator<IndexSplit, String> createEnumerator(EnumeratorContext<IndexSplit> context) {
            return new RowsEnumerator(context, rows().size(), splits);
        }

        @Override
        public SplitEnumerator<IndexSplit, String> restoreEnumerator(
                EnumeratorContext<IndexSplit> context, String state) {
            return createEnumerator(context);
        }

        @Override
        public SourceReader<IndexSplit> createReader(ReaderContext context) {
            List<SchemaChangeEvent> changes = schemaChange
                    ? tables().stream()
                            .<SchemaChangeEvent>map(t ->
                                    new AddColumn(t.table().id(), Column.of(ADDED_COLUMN, DataTypes.STRING()), null))
                            .toList()
                    : List.of();
            return new RowsReader(rows(), changes, context);
        }

        @Override
        public VersionedSerializer<IndexSplit> splitSerializer() {
            return new IndexSplitSerializer();
        }

        @Override
        public VersionedSerializer<String> enumeratorStateSerializer() {
            return StringSerializer.INSTANCE;
        }
    }

    private static final class RowsEnumerator implements SplitEnumerator<IndexSplit, String> {
        private final EnumeratorContext<IndexSplit> context;
        private final Deque<IndexSplit> pending = new ArrayDeque<>();

        RowsEnumerator(EnumeratorContext<IndexSplit> context, int rows, int splits) {
            this.context = context;
            int size = Math.max(1, (rows + splits - 1) / splits);
            for (int from = 0, i = 0; from < rows; from += size, i++) {
                pending.add(new IndexSplit("split-" + i, from, Math.min(rows, from + size)));
            }
        }

        @Override
        public void start() {}

        @Override
        public void handleSplitRequest(int readerId) {
            IndexSplit next = pending.poll();
            if (next == null) {
                context.signalNoMoreSplits(readerId);
            } else {
                context.assignSplit(next, readerId);
            }
        }

        @Override
        public void addSplitsBack(List<IndexSplit> splits, int readerId) {
            pending.addAll(splits);
        }

        @Override
        public void addReader(int readerId) {}

        @Override
        public String snapshotState(long checkpointId) {
            return "";
        }
    }

    private static final class RowsReader implements SourceReader<IndexSplit> {
        private final List<Row> rows;
        private final List<SchemaChangeEvent> changes;
        private final ReaderContext context;
        private final Deque<IndexSplit> splits = new ArrayDeque<>();
        private @Nullable IndexSplit current;
        private int next;
        private boolean noMore;

        RowsReader(List<Row> rows, List<SchemaChangeEvent> changes, ReaderContext context) {
            this.rows = rows;
            this.changes = changes;
            this.context = context;
        }

        @Override
        public void start() {
            context.sendSplitRequest();
        }

        @Override
        public void addSplits(List<IndexSplit> added) {
            splits.addAll(added);
        }

        @Override
        public void notifyNoMoreSplits() {
            noMore = true;
        }

        @Override
        public PollStatus pollNext(ReaderOutput output) {
            IndexSplit split = current;
            if (split == null) {
                split = splits.poll();
                if (split == null) {
                    return noMore ? PollStatus.END_OF_INPUT : PollStatus.NOTHING_AVAILABLE;
                }
                current = split;
                next = split.from();
            }
            int end = Math.min(split.to(), next + 256);
            for (; next < end; next++) {
                output.collect(rows.get(next));
            }
            if (next >= split.to()) {
                if (split.from() == 0) {
                    changes.forEach(output::collect);
                }
                current = null;
                context.sendSplitRequest();
            }
            return PollStatus.MORE_AVAILABLE;
        }

        @Override
        public List<IndexSplit> snapshotState(long checkpointId) {
            List<IndexSplit> state = new ArrayList<>(splits);
            IndexSplit split = current;
            if (split != null) {
                state.add(0, new IndexSplit(split.splitId(), next, split.to()));
            }
            return state;
        }
    }

    // ------------------------------------------------------------------ sink

    private static final class CollectFactory implements SinkFactory {
        @Override
        public String identifier() {
            return COLLECT;
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder().required(DATASET).build();
        }

        @Override
        public Sink<?, ?> createSink(FactoryContext context) {
            return new CollectSink(context.options().get(DATASET));
        }
    }

    private static final class CollectSink implements Sink<String, String> {
        private static final long serialVersionUID = 1L;
        private final String dataset;

        CollectSink(String dataset) {
            this.dataset = dataset;
        }

        @Override
        public void validate(List<CatalogTable> inputs, SinkContext context) {}

        @Override
        public SinkWriter<String, String> createWriter(WriterContext context, List<String> restored) {
            return new CollectWriter(dataset);
        }

        @Override
        public VersionedSerializer<String> committableSerializer() {
            return StringSerializer.INSTANCE;
        }

        @Override
        public VersionedSerializer<String> writerStateSerializer() {
            return StringSerializer.INSTANCE;
        }
    }

    private static final class CollectWriter implements SinkWriter<String, String> {
        private final String dataset;
        private final List<Row> buffer = new ArrayList<>();

        CollectWriter(String dataset) {
            this.dataset = dataset;
        }

        @Override
        public void write(Row row) {
            buffer.add(row);
        }

        @Override
        public void applySchemaChange(SchemaChangeEvent event) {
            flush();
            Datasets.changes(dataset).add(event);
        }

        @Override
        public void flush() {
            Datasets.sink(dataset).addAll(buffer);
            buffer.clear();
        }
    }
}
