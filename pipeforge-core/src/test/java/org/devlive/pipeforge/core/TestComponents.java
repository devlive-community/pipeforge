/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core;

import static org.devlive.pipeforge.api.type.DataTypes.DECIMAL;
import static org.devlive.pipeforge.api.type.DataTypes.INT32;
import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;
import static org.devlive.pipeforge.api.type.DataTypes.TIMESTAMP;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.api.capability.SupportsUpsert;
import org.devlive.pipeforge.api.expression.ExpressionInfo;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkContext;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceContext;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.source.SourceSplit;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.transform.Collector;
import org.devlive.pipeforge.api.transform.Transform;
import org.devlive.pipeforge.api.transform.TransformContext;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.JobDefinition;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.Planner;
import org.devlive.pipeforge.core.plan.StaticComponents;
import org.devlive.pipeforge.core.variable.RunInfo;
import org.devlive.pipeforge.core.variable.VariableContext;

/**
 * Components for planning tests. Their runtime methods are never called while planning and throw.
 *
 * <ul>
 *   <li>source {@code fake}: table {@code demo.orders} (id, name STRING(20), amount, created_at),
 *       options {@code rows}, {@code table}, {@code password} (sensitive), {@code fetch-size}
 *   <li>source {@code events}: unbounded, table {@code demo.orders} with name STRING(40), id INT32
 *       and an extra {@code channel} column
 *   <li>source {@code clicks}: table {@code demo.orders} where {@code id} is a STRING
 *   <li>transform {@code rename}: renames column {@code from} to {@code to}
 *   <li>transform {@code drop-all}: produces no tables
 *   <li>transform {@code broken}: fails while deriving the schema
 *   <li>sink {@code console}: option {@code limit}; sink {@code keyed}: requires a primary key and
 *       is idempotent; sink {@code committed}: has a committer
 * </ul>
 */
public final class TestComponents {

    /** The planned time of the test runs: 2026-11-07 00:30 UTC, 08:30 in Asia/Shanghai. */
    public static final Instant SCHEDULED = Instant.parse("2026-11-07T00:30:00Z");

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    public static final TableId ORDERS = TableId.of("demo", "orders");

    static final Option<Integer> ROWS =
            Options.key("rows").intType().defaultValue(10).withRange(0, 1_000_000);
    static final Option<String> TABLE = Options.key("table").stringType().defaultValue("demo.orders");
    static final Option<String> PASSWORD =
            Options.key("password").stringType().noDefault().withSensitive(true);
    static final Option<Integer> FETCH_SIZE =
            Options.key("fetch-size").intType().defaultValue(100);
    static final Option<Map<String, String>> PROPERTIES =
            Options.key("properties").mapType().noDefault();
    static final Option<String> FROM = Options.key("from").stringType().noDefault();
    static final Option<String> TO = Options.key("to").stringType().noDefault();
    static final Option<Integer> LIMIT = Options.key("limit").intType().defaultValue(20);
    static final Option<String> EXPR = Options.key("expr").stringType().noDefault();
    public static final Option<List<Map<String, String>>> NOTES =
            Options.key("notes").objectListType().noDefault();

    private TestComponents() {}

    public static StaticComponents components() {
        return StaticComponents.builder()
                .source(source("fake", Boundedness.BOUNDED, TestComponents::orders))
                .source(source(
                        "events",
                        Boundedness.UNBOUNDED,
                        config -> List.of(CatalogTable.of(
                                ORDERS,
                                List.of(
                                        Column.of("id", INT32().notNull()),
                                        Column.of("name", STRING(40)),
                                        Column.of("channel", STRING()))))))
                .source(source(
                        "clicks",
                        Boundedness.BOUNDED,
                        config -> List.of(CatalogTable.of(ORDERS, List.of(Column.of("id", STRING()))))))
                .transform(transform(
                        "rename", OptionRule.builder().required(FROM, TO).build(), (config, inputs) -> {
                            List<CatalogTable> out = new ArrayList<>();
                            for (CatalogTable table : inputs) {
                                List<Column> columns = table.columns().stream()
                                        .map(c -> c.name().equals(config.get(FROM)) ? c.withName(config.get(TO)) : c)
                                        .toList();
                                out.add(table.withColumns(columns));
                            }
                            return out;
                        }))
                .transform(compute())
                .transform(transform("drop-all", OptionRule.builder().build(), (config, inputs) -> List.of()))
                .transform(transform("broken", OptionRule.builder().build(), (config, inputs) -> {
                    throw new IllegalStateException("cannot derive");
                }))
                .sink(sink("console", false, false))
                .sink(sink("keyed", true, false))
                .sink(sink("committed", false, true))
                .build();
    }

    /** The option rules of the test components, by kind and identifier, as the job schema takes them. */
    public static Map<NodeKind, Map<String, OptionRule>> rules() {
        Map<NodeKind, Map<String, OptionRule>> rules = new java.util.EnumMap<>(NodeKind.class);
        rules.put(
                NodeKind.SOURCE,
                Map.of(
                        "fake",
                        source("fake", Boundedness.BOUNDED, TestComponents::orders)
                                .optionRule()));
        rules.put(
                NodeKind.TRANSFORM,
                Map.of("rename", OptionRule.builder().required(FROM, TO).build()));
        rules.put(NodeKind.SINK, Map.of("console", sink("console", false, false).optionRule()));
        return rules;
    }

    /** Parses a job with the test context: scheduled time {@link #SCHEDULED}, Shanghai, fixed env. */
    public static JobDefinition parse(String yaml) {
        return JobParser.parse(yaml, context().build());
    }

    public static VariableContext.Builder context() {
        return VariableContext.builder()
                .scheduledTime(SCHEDULED)
                .defaultTimeZone(SHANGHAI)
                .environment(Map.of("REGION", "cn-east", "HOME", "/home/pf"))
                .run(new RunInfo("run-42", 2, Instant.parse("2026-11-07T00:31:15.123456Z")));
    }

    public static JobPlan plan(String yaml) {
        return new Planner(components()).plan(parse(yaml));
    }

    public static JobPlan plan(String yaml, ReadonlyConfig cluster) {
        return new Planner(components(), cluster).plan(parse(yaml));
    }

    /** The factory of a test source by name, for wrapping in plugin factories. */
    public static SourceFactory sourceFactory(String identifier, Boundedness boundedness) {
        return source(identifier, boundedness, TestComponents::orders);
    }

    /** The factory of a test sink that accepts everything. */
    public static SinkFactory sinkFactory(String identifier) {
        return sink(identifier, false, false);
    }

    static List<CatalogTable> orders(ReadonlyConfig config) {
        return List.of(CatalogTable.of(
                        TableId.parse(config.get(TABLE)),
                        List.of(
                                Column.of("id", INT64().notNull()),
                                Column.of("name", STRING(20)).withComment("customer name"),
                                Column.of("amount", DECIMAL(10, 2)),
                                Column.of("created_at", TIMESTAMP(3))))
                .withPrimaryKey("id"));
    }

    // ------------------------------------------------------------------ factories

    interface Tables {
        List<CatalogTable> derive(ReadonlyConfig config);
    }

    interface Schema {
        List<CatalogTable> derive(ReadonlyConfig config, List<CatalogTable> inputs);
    }

    static SourceFactory source(String identifier, Boundedness boundedness, Tables tables) {
        return new SourceFactory() {
            @Override
            public String identifier() {
                return identifier;
            }

            @Override
            public OptionRule optionRule() {
                return OptionRule.builder()
                        .optional(ROWS, TABLE, PASSWORD, FETCH_SIZE, PROPERTIES)
                        .nativeNamespaces("native")
                        .build();
            }

            @Override
            public Source<?, ?> createSource(FactoryContext context) {
                return new TestSource(boundedness, tables.derive(context.options()));
            }
        };
    }

    static TransformFactory transform(String identifier, OptionRule rule, Schema schema) {
        return new TransformFactory() {
            @Override
            public String identifier() {
                return identifier;
            }

            @Override
            public OptionRule optionRule() {
                return rule;
            }

            @Override
            public Transform createTransform(FactoryContext context) {
                ReadonlyConfig config = context.options();
                return new Transform() {
                    @Override
                    public List<CatalogTable> transformSchema(List<CatalogTable> inputs) {
                        return schema.derive(config, inputs);
                    }

                    @Override
                    public void open(TransformContext ctx) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void process(Row row, Collector<Row> out) {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    /** Appends a column {@code to} computed by {@code expr}; its type comes from the expression language. */
    static TransformFactory compute() {
        return new TransformFactory() {
            @Override
            public String identifier() {
                return "compute";
            }

            @Override
            public OptionRule optionRule() {
                return OptionRule.builder().required(EXPR, TO).optional(NOTES).build();
            }

            @Override
            public Transform createTransform(FactoryContext context) {
                ReadonlyConfig config = context.options();
                return new Transform() {
                    @Override
                    public List<CatalogTable> transformSchema(List<CatalogTable> inputs) {
                        List<CatalogTable> out = new ArrayList<>();
                        for (CatalogTable table : inputs) {
                            ExpressionInfo info = context.expressions()
                                    .analyze(List.of(config.get(EXPR)), table.columns())
                                    .get(0);
                            List<Column> columns = new ArrayList<>(table.columns());
                            columns.add(Column.of(config.get(TO), info.type()));
                            out.add(table.withColumns(columns));
                        }
                        return out;
                    }

                    @Override
                    public void open(TransformContext ctx) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void process(Row row, Collector<Row> out) {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    static SinkFactory sink(String identifier, boolean requiresKey, boolean committer) {
        return new SinkFactory() {
            @Override
            public String identifier() {
                return identifier;
            }

            @Override
            public OptionRule optionRule() {
                return OptionRule.builder().optional(LIMIT).build();
            }

            @Override
            public Sink<?, ?> createSink(FactoryContext context) {
                return requiresKey ? new KeyedSink() : new TestSink(committer);
            }
        };
    }

    record TestSource(Boundedness boundedness, List<CatalogTable> tables) implements Source<SourceSplit, Void> {
        @Override
        public List<CatalogTable> producedTables(SourceContext context) {
            return tables;
        }

        @Override
        public SplitEnumerator<SourceSplit, Void> createEnumerator(EnumeratorContext<SourceSplit> context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SplitEnumerator<SourceSplit, Void> restoreEnumerator(
                EnumeratorContext<SourceSplit> context, Void state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SourceReader<SourceSplit> createReader(ReaderContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VersionedSerializer<SourceSplit> splitSerializer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VersionedSerializer<Void> enumeratorStateSerializer() {
            throw new UnsupportedOperationException();
        }
    }

    static class TestSink implements Sink<String, Void> {
        private final boolean committer;

        TestSink(boolean committer) {
            this.committer = committer;
        }

        @Override
        public void validate(List<CatalogTable> inputs, SinkContext context) {}

        @Override
        public SinkWriter<String, Void> createWriter(WriterContext context, List<Void> restored) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<org.devlive.pipeforge.api.sink.SinkCommitter<String>> createCommitter() {
            if (!committer) {
                return Optional.empty();
            }
            return Optional.of(new org.devlive.pipeforge.api.sink.SinkCommitter<>() {
                @Override
                public List<String> commit(List<String> committables) {
                    return List.of();
                }

                @Override
                public void abort(List<String> committables) {}
            });
        }

        @Override
        public VersionedSerializer<String> committableSerializer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VersionedSerializer<Void> writerStateSerializer() {
            throw new UnsupportedOperationException();
        }
    }

    /** Requires a primary key on every table it receives. */
    static final class KeyedSink extends TestSink implements SupportsUpsert {
        KeyedSink() {
            super(false);
        }

        @Override
        public void validate(List<CatalogTable> inputs, SinkContext context) {
            for (CatalogTable table : inputs) {
                if (table.primaryKey().isEmpty()) {
                    throw PipeForgeException.of(ErrorCode.UPSERT_REQUIRES_PK)
                            .param("node", context.nodeName())
                            .param("table", table.id().toString())
                            .build();
                }
            }
        }
    }
}
