/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.testing;

import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.api.expression.OnError;
import org.devlive.pipeforge.api.expression.RowEvaluator;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.serialization.StringSerializer;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.GlobalCommitter;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkCommitter;
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
import org.devlive.pipeforge.api.transform.Collector;
import org.devlive.pipeforge.api.transform.Transform;
import org.devlive.pipeforge.api.transform.TransformContext;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.Planner;
import org.devlive.pipeforge.core.plan.StaticComponents;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.jspecify.annotations.Nullable;

/**
 * Connectors for engine tests.
 *
 * <ul>
 *   <li>source {@code numbers}: {@code rows} rows {@code (id, name)} in {@code splits} splits that
 *       readers pull one at a time; can fail, block or emit a schema change;
 *   <li>transform {@code upper}: upper-cases {@code name}; can drop odd ids, duplicate rows or fail;
 *   <li>sink {@code collect}: records rows in the {@link Probe}; can be slow, fail, block, and
 *       commit in two phases.
 * </ul>
 */
public final class TestConnectors {

    public static final TableId NUMBERS = TableId.of("demo", "numbers");

    static final Option<String> PROBE = Options.key("probe").stringType().noDefault();
    static final Option<Integer> ROWS = Options.key("rows").intType().defaultValue(10);
    static final Option<Integer> SPLITS = Options.key("splits").intType().defaultValue(2);
    static final Option<Integer> FAIL_AT = Options.key("fail-at").intType().defaultValue(-1);
    static final Option<String> FAIL_WITH =
            Options.key("fail-with").stringType().defaultValue("runtime");
    static final Option<String> BLOCK = Options.key("block").stringType().defaultValue("none");
    static final Option<Boolean> SCHEMA_CHANGE =
            Options.key("schema-change").booleanType().defaultValue(false);
    static final Option<Boolean> FAIL_ENUMERATOR =
            Options.key("fail-enumerator").booleanType().defaultValue(false);
    static final Option<Boolean> DROP_ODD =
            Options.key("drop-odd").booleanType().defaultValue(false);
    static final Option<Boolean> DUPLICATE =
            Options.key("duplicate").booleanType().defaultValue(false);
    /** An expression that replaces the name, evaluated with the expression language of the engine. */
    static final Option<String> EXPR = Options.key("expr").stringType().noDefault();

    static final Option<Integer> SLOW_MS = Options.key("slow-ms").intType().defaultValue(0);
    static final Option<String> COMMIT = Options.key("commit").stringType().defaultValue("none");

    private TestConnectors() {}

    public static StaticComponents components() {
        return StaticComponents.builder()
                .source(new NumbersFactory())
                .transform(new UpperFactory())
                .sink(new CollectFactory())
                .build();
    }

    /** Plans a job written in YAML with the test connectors. */
    public static JobPlan plan(String yaml) {
        VariableContext context = VariableContext.builder()
                .scheduledTime(Instant.parse("2026-11-07T00:30:00Z"))
                .defaultTimeZone(ZoneId.of("UTC"))
                .environment(Map.of())
                .build();
        return new Planner(components()).plan(JobParser.parse(yaml, context));
    }

    /**
     * A job {@code numbers → [upper] → collect}.
     *
     * @param probe the probe name
     * @param source extra options of the source, e.g. {@code "rows: 5, fail-at: 3"}
     * @param transform options of the transform, or {@code null} for no transform
     * @param sink extra options of the sink
     * @param parallelism the job parallelism
     */
    public static JobPlan job(String probe, String source, @Nullable String transform, String sink, int parallelism) {
        StringBuilder yaml =
                new StringBuilder("version: 1\nname: test-job\nenv:\n  parallelism: " + parallelism + "\n");
        yaml.append("source:\n  - { id: src, type: numbers, probe: ")
                .append(probe)
                .append(source.isEmpty() ? "" : ", " + source)
                .append(" }\n");
        if (transform != null) {
            yaml.append("transform:\n  - { id: up, type: upper, probe: ")
                    .append(probe)
                    .append(transform.isEmpty() ? "" : ", " + transform)
                    .append(" }\n");
        }
        yaml.append("sink:\n  - { id: out, type: collect, probe: ")
                .append(probe)
                .append(sink.isEmpty() ? "" : ", " + sink)
                .append(" }\n");
        return plan(yaml.toString());
    }

    static Exception failure(ReadonlyConfig config, String what) {
        return switch (config.get(FAIL_WITH)) {
            case "coded" -> PipeForgeException.of(ErrorCode.EXPRESSION_ERROR)
                    .param("expression", what)
                    .param("reason", "boom")
                    .build();
            case "io" -> new IOException(what + " broke");
            default -> new IllegalStateException(what + " broke");
        };
    }

    static CatalogTable table() {
        return CatalogTable.of(NUMBERS, List.of(Column.of("id", INT64().notNull()), Column.of("name", STRING())))
                .withPrimaryKey("id");
    }

    /** Blocks the calling thread the way {@code mode} says; returns when cancelled or released. */
    static void block(String mode, Probe probe, java.util.concurrent.CountDownLatch cancelled) throws Exception {
        probe.blocking.countDown();
        switch (mode) {
            case "cancel" -> cancelled.await();
            case "interrupt" -> {
                // ignores cancel(); only an interrupt ends the wait
                probe.release.await();
            }
            case "forever" -> {
                while (probe.release.getCount() > 0) {
                    try {
                        probe.release.await();
                    } catch (InterruptedException e) {
                        // ignored on purpose: the task must be given up
                    }
                }
            }
            default -> {
                // not blocking
            }
        }
    }

    // ------------------------------------------------------------------ source

    /** A split of the {@code numbers} source, serialized as the job master sends it. */
    public static org.devlive.pipeforge.engine.rpc.SerializedValue split(String id, long from, long to) {
        try {
            return org.devlive.pipeforge.engine.rpc.SerializedValue.of(
                    new SplitSerializer(), new NumberSplit(id, from, to));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** A range of ids {@code [from, to)}. */
    record NumberSplit(String splitId, long from, long to) implements SourceSplit {}

    static final class SplitSerializer implements VersionedSerializer<NumberSplit> {
        @Override
        public int version() {
            return 1;
        }

        @Override
        public byte[] serialize(NumberSplit value) {
            return (value.splitId() + ":" + value.from() + ":" + value.to()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public NumberSplit deserialize(int version, byte[] bytes) throws IOException {
            String[] parts = new String(bytes, StandardCharsets.UTF_8).split(":");
            if (version != 1 || parts.length != 3) {
                throw new IOException("bad split");
            }
            return new NumberSplit(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        }
    }

    static final class NumbersFactory implements SourceFactory {
        @Override
        public String identifier() {
            return "numbers";
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder()
                    .required(PROBE)
                    .optional(ROWS, SPLITS, FAIL_AT, FAIL_WITH, BLOCK, SCHEMA_CHANGE, FAIL_ENUMERATOR)
                    .build();
        }

        @Override
        public Source<?, ?> createSource(FactoryContext context) {
            return new NumbersSource(context.options());
        }
    }

    static final class NumbersSource implements Source<NumberSplit, String> {
        private static final long serialVersionUID = 1L;
        private final transient ReadonlyConfig config;

        NumbersSource(ReadonlyConfig config) {
            this.config = config;
        }

        @Override
        public Boundedness boundedness() {
            return Boundedness.BOUNDED;
        }

        @Override
        public List<CatalogTable> producedTables(SourceContext context) {
            return List.of(table());
        }

        @Override
        public SplitEnumerator<NumberSplit, String> createEnumerator(EnumeratorContext<NumberSplit> context) {
            return new NumbersEnumerator(config, context);
        }

        @Override
        public SplitEnumerator<NumberSplit, String> restoreEnumerator(
                EnumeratorContext<NumberSplit> context, String state) {
            return new NumbersEnumerator(config, context);
        }

        @Override
        public SourceReader<NumberSplit> createReader(ReaderContext context) {
            return new NumbersReader(config, context);
        }

        @Override
        public VersionedSerializer<NumberSplit> splitSerializer() {
            return new SplitSerializer();
        }

        @Override
        public VersionedSerializer<String> enumeratorStateSerializer() {
            return StringSerializer.INSTANCE;
        }
    }

    static final class NumbersEnumerator implements SplitEnumerator<NumberSplit, String> {
        private final ReadonlyConfig config;
        private final EnumeratorContext<NumberSplit> context;
        private final Deque<NumberSplit> pending = new ArrayDeque<>();
        private final Probe probe;

        NumbersEnumerator(ReadonlyConfig config, EnumeratorContext<NumberSplit> context) {
            this.config = config;
            this.context = context;
            this.probe = Probe.get(config.get(PROBE));
        }

        @Override
        public void start() {
            probe.enumeratorThreads.add(Thread.currentThread().getName());
            if (config.get(FAIL_ENUMERATOR)) {
                throw new IllegalStateException("cannot list the splits");
            }
            int rows = config.get(ROWS);
            int splits = config.get(SPLITS);
            long size = (rows + splits - 1L) / Math.max(1, splits);
            for (int i = 0; i < splits; i++) {
                long from = i * size;
                long to = Math.min(rows, from + size);
                if (from < to) {
                    pending.add(new NumberSplit("split-" + i, from, to));
                }
            }
        }

        @Override
        public void handleSplitRequest(int readerId) {
            probe.enumeratorThreads.add(Thread.currentThread().getName());
            NumberSplit next = pending.poll();
            if (next == null) {
                context.signalNoMoreSplits(readerId);
            } else {
                probe.splitsPerReader.add(readerId);
                context.assignSplit(next, readerId);
            }
        }

        @Override
        public void addSplitsBack(List<NumberSplit> splits, int readerId) {
            pending.addAll(splits);
        }

        @Override
        public void addReader(int readerId) {
            probe.events.add("reader " + readerId + " registered "
                    + context.registeredReaders().contains(readerId));
        }

        @Override
        public String snapshotState(long checkpointId) {
            return "";
        }

        @Override
        public void close() {
            probe.events.add("enumerator closed");
        }
    }

    static final class NumbersReader implements SourceReader<NumberSplit> {
        private final ReadonlyConfig config;
        private final ReaderContext context;
        private final Probe probe;
        private final Deque<NumberSplit> splits = new ArrayDeque<>();
        private final java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        private @Nullable NumberSplit current;
        private long next;
        private boolean noMore;
        private boolean schemaChangeSent;
        private int emitted;

        NumbersReader(ReadonlyConfig config, ReaderContext context) {
            this.config = config;
            this.context = context;
            this.probe = Probe.get(config.get(PROBE));
        }

        @Override
        public void start() {
            context.sendSplitRequest();
        }

        @Override
        public void addSplits(List<NumberSplit> added) {
            splits.addAll(added);
        }

        @Override
        public void notifyNoMoreSplits() {
            noMore = true;
        }

        @Override
        public PollStatus pollNext(ReaderOutput output) throws Exception {
            probe.readerThreads.add(Thread.currentThread().getName());
            if (!"none".equals(config.get(BLOCK))) {
                block(config.get(BLOCK), probe, cancelled);
            }
            if (current == null) {
                current = splits.poll();
                if (current == null) {
                    return noMore ? PollStatus.END_OF_INPUT : PollStatus.NOTHING_AVAILABLE;
                }
                next = current.from();
            }
            if (config.get(SCHEMA_CHANGE) && !schemaChangeSent) {
                schemaChangeSent = true;
                output.collect((SchemaChangeEvent) new AddColumn(NUMBERS, Column.of("extra", STRING()), null));
            }
            if (emitted == config.get(FAIL_AT)) {
                throw failure(config, "reading row " + emitted);
            }
            output.collect(GenericRow.of(NUMBERS, next, "name-" + next));
            emitted++;
            next++;
            if (next >= current.to()) {
                current = null;
                context.sendSplitRequest();
            }
            return PollStatus.MORE_AVAILABLE;
        }

        @Override
        public List<NumberSplit> snapshotState(long checkpointId) {
            return List.copyOf(splits);
        }

        @Override
        public void cancel() {
            if ("cancel".equals(config.get(BLOCK))) {
                cancelled.countDown();
            }
        }

        @Override
        public void close() {
            probe.events.add("reader " + context.subtaskIndex() + " closed");
        }
    }

    // ------------------------------------------------------------------ transform

    static final class UpperFactory implements TransformFactory {
        @Override
        public String identifier() {
            return "upper";
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder()
                    .required(PROBE)
                    .optional(FAIL_AT, FAIL_WITH, DROP_ODD, DUPLICATE, EXPR)
                    .build();
        }

        @Override
        public Transform createTransform(FactoryContext context) {
            ReadonlyConfig config = context.options();
            if (config.contains(EXPR)) {
                // type-checked while planning and again when the task creates the transform
                context.expressions().analyze(List.of(config.get(EXPR)), table().columns());
            }
            return new UpperTransform(config);
        }
    }

    static final class UpperTransform implements Transform {
        private static final long serialVersionUID = 1L;
        private final transient ReadonlyConfig config;
        private transient int seen;
        private transient @Nullable RowEvaluator expression;

        UpperTransform(ReadonlyConfig config) {
            this.config = config;
        }

        @Override
        public List<CatalogTable> transformSchema(List<CatalogTable> inputs) {
            return inputs;
        }

        @Override
        public void open(TransformContext context) {
            if (config.contains(EXPR)) {
                expression = context.expressions()
                        .compile(
                                List.of(config.get(EXPR)),
                                context.inputTables().get(0).columns(),
                                OnError.FAIL);
            }
            Probe.get(config.get(PROBE))
                    .events
                    .add("transform opened " + context.subtaskIndex() + "/" + context.parallelism() + " "
                            + context.inputTables().size());
        }

        @Override
        public void process(Row row, Collector<Row> out) throws Exception {
            if (seen++ == config.get(FAIL_AT)) {
                throw failure(config, "transforming row " + (seen - 1));
            }
            long id = (Long) row.get(0);
            if (config.get(DROP_ODD) && id % 2 == 1) {
                return;
            }
            RowEvaluator evaluator = expression;
            Object name;
            if (evaluator == null) {
                name = String.valueOf(row.get(1)).toUpperCase(Locale.ROOT);
            } else {
                Object[] value = new Object[1];
                evaluator.evaluate(row, value);
                name = String.valueOf(value[0]);
            }
            Row upper = GenericRow.of(row.table(), id, name);
            out.collect(upper);
            if (config.get(DUPLICATE)) {
                out.collect(upper);
            }
        }

        @Override
        public SchemaChangeEvent transformSchemaChange(SchemaChangeEvent event) {
            Probe.get(config.get(PROBE))
                    .events
                    .add("transform saw " + event.getClass().getSimpleName());
            return event;
        }
    }

    // ------------------------------------------------------------------ sink

    static final class CollectFactory implements SinkFactory {
        @Override
        public String identifier() {
            return "collect";
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder()
                    .required(PROBE)
                    .optional(FAIL_AT, FAIL_WITH, SLOW_MS, BLOCK, COMMIT)
                    .build();
        }

        @Override
        public Sink<?, ?> createSink(FactoryContext context) {
            return new CollectSink(context.options());
        }
    }

    static final class CollectSink implements Sink<String, String> {
        private static final long serialVersionUID = 1L;
        private final transient ReadonlyConfig config;

        CollectSink(ReadonlyConfig config) {
            this.config = config;
        }

        @Override
        public void validate(List<CatalogTable> inputs, SinkContext context) {}

        @Override
        public SinkWriter<String, String> createWriter(WriterContext context, List<String> restored) {
            return new CollectWriter(config, context);
        }

        @Override
        public Optional<SinkCommitter<String>> createCommitter() {
            Probe probe = Probe.get(config.get(PROBE));
            return switch (config.get(COMMIT)) {
                case "two-phase", "global" -> Optional.of(new SinkCommitter<>() {
                    @Override
                    public List<String> commit(List<String> committables) {
                        probe.commitAttempts++;
                        probe.committed.addAll(committables);
                        return List.of();
                    }

                    @Override
                    public void abort(List<String> committables) {}
                });
                case "retry-forever" -> Optional.of(new SinkCommitter<>() {
                    @Override
                    public List<String> commit(List<String> committables) {
                        probe.commitAttempts++;
                        return committables;
                    }

                    @Override
                    public void abort(List<String> committables) {}
                });
                case "fail" -> Optional.of(new SinkCommitter<>() {
                    @Override
                    public List<String> commit(List<String> committables) throws IOException {
                        throw new IOException("target unavailable");
                    }

                    @Override
                    public void abort(List<String> committables) {}
                });
                default -> Optional.empty();
            };
        }

        @Override
        public Optional<GlobalCommitter<String, ?>> createGlobalCommitter() {
            if (!"global".equals(config.get(COMMIT))) {
                return Optional.empty();
            }
            Probe probe = Probe.get(config.get(PROBE));
            return Optional.of(new GlobalCommitter<String, String>() {
                @Override
                public String combine(List<String> committables) {
                    return String.join("+", committables.stream().sorted().toList());
                }

                @Override
                public void commit(List<String> globals) {
                    probe.globallyCommitted.addAll(globals);
                }
            });
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

    static final class CollectWriter implements SinkWriter<String, String> {
        private final ReadonlyConfig config;
        private final WriterContext context;
        private final Probe probe;
        private final List<Row> buffered = new ArrayList<>();
        private final java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        private int written;

        CollectWriter(ReadonlyConfig config, WriterContext context) {
            this.config = config;
            this.context = context;
            this.probe = Probe.get(config.get(PROBE));
        }

        @Override
        public void write(Row row) throws Exception {
            probe.writerThreads.add(Thread.currentThread().getName());
            if (written == config.get(FAIL_AT)) {
                throw failure(config, "writing row " + written);
            }
            if (!"none".equals(config.get(BLOCK))) {
                block(config.get(BLOCK), probe, cancelled);
            }
            int slow = config.get(SLOW_MS);
            if (slow > 0) {
                Thread.sleep(slow);
            }
            buffered.add(row);
            written++;
        }

        @Override
        public void applySchemaChange(SchemaChangeEvent event) {
            probe.schemaChanges.add(event);
        }

        @Override
        public void flush() {
            probe.rows.addAll(buffered);
            buffered.clear();
        }

        @Override
        public Optional<String> prepareCommit(long checkpointId) {
            flush();
            if ("none".equals(config.get(COMMIT))) {
                return Optional.empty();
            }
            return Optional.of(context.subtaskIndex() + ":" + written + "@" + checkpointId);
        }
    }
}
