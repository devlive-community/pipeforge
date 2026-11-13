/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32C;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.core.plan.PlannedOption;
import org.devlive.pipeforge.engine.rpc.proto.ColumnEntry;
import org.devlive.pipeforge.engine.rpc.proto.NodeDescriptor;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.engine.rpc.proto.TableEntry;
import org.jspecify.annotations.Nullable;

/**
 * The task description of protocol v1 (protocol specification, section 6): what a worker needs to
 * build an operator chain, as {@code magic "PFCH" | format version | length | Protobuf
 * ChainDescriptor | CRC32C}, all integers big-endian.
 *
 * <p>Options a plugin marks as sensitive never enter the description: their values move to a
 * separate map, keyed {@code <node>.<option>}, and the description holds {@code {"$secret":
 * "<node>.<option>"}} in their place. The transport encrypts that map for the worker.
 *
 * <p>A description names components by plugin and identifier; the receiving side resolves them
 * with its own plugins.
 */
public final class ChainCodec {

    /** {@code "PFCH"}. */
    public static final int MAGIC = 0x50464348;

    /** The format version this build writes; it reads every version up to it. */
    public static final int FORMAT_VERSION = 1;

    /** The key of the placeholder that stands for a sensitive value. */
    public static final String SECRET_KEY = "$secret";

    private static final int HEADER_BYTES = 12;
    private static final int TRAILER_BYTES = 4;
    private static final String BUILTIN = "builtin";
    /** Object keys sorted, so that the same options always give the same bytes. */
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};

    private ChainCodec() {}

    /**
     * What a description says about the run beyond the chain itself.
     *
     * @param scheduledTime the planned time of the run, or {@code null}
     * @param mode {@code batch} or {@code streaming}
     * @param delivery {@code at-least-once} or {@code exactly-once}
     */
    public record RunContext(
            String jobId,
            String runId,
            Map<String, String> variables,
            Instant runStart,
            @Nullable Instant scheduledTime,
            String mode,
            String delivery) {

        public RunContext {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(runStart, "runStart");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(delivery, "delivery");
            variables = Map.copyOf(variables);
        }
    }

    /** A description and the sensitive values that were taken out of it. */
    public record Encoded(byte[] descriptor, Map<String, String> secrets) {
        public Encoded {
            descriptor = descriptor.clone();
            secrets = Map.copyOf(secrets);
        }

        @Override
        public byte[] descriptor() {
            return descriptor.clone();
        }
    }

    /** A decoded description: the chain with resolved components, and its run. */
    public record Decoded(ChainDescriptor chain, RunContext run) {}

    /** Finds the component a node of a description names. */
    @FunctionalInterface
    public interface ComponentResolver {
        /**
         * @param plugin {@code id@version} of the plugin, or {@code builtin}
         */
        Component resolve(NodeKind kind, String type, String plugin);
    }

    // ---------------------------------------------------------------------------------- encode

    /**
     * Writes the description of {@code chain}.
     *
     * @throws IllegalArgumentException if an option value cannot be written as JSON
     */
    public static Encoded encode(ChainDescriptor chain, RunContext run) {
        TableDirectory tables = new TableDirectory();
        Map<String, String> secrets = new LinkedHashMap<>();
        var builder = org.devlive.pipeforge.engine.rpc.proto.ChainDescriptor.newBuilder()
                .setVertexId(chain.vertexId())
                .setJob(job(chain.job(), run));
        for (PlannedNode node : chain.nodes()) {
            builder.addNodes(node(node, tables, secrets));
        }
        builder.addAllTables(tables.entries());
        return new Encoded(wrap(builder.build()), secrets);
    }

    private static org.devlive.pipeforge.engine.rpc.proto.JobContext job(JobContext job, RunContext run) {
        var builder = org.devlive.pipeforge.engine.rpc.proto.JobContext.newBuilder()
                .setJobName(job.jobName())
                .setParallelism(job.parallelism())
                .setTimeZone(job.timeZone().getId())
                .setJobId(run.jobId())
                .setRunId(run.runId())
                .putAllVariables(run.variables())
                .setRunStartMs(run.runStart().toEpochMilli())
                .setMode(run.mode())
                .setDelivery(run.delivery());
        if (run.scheduledTime() != null) {
            builder.setScheduledTimeMs(run.scheduledTime().toEpochMilli());
        }
        return builder.build();
    }

    private static NodeDescriptor node(PlannedNode node, TableDirectory tables, Map<String, String> secrets) {
        Map<String, Object> options = new LinkedHashMap<>(node.config().toMap());
        for (PlannedOption option : node.options()) {
            if (option.sensitive() && options.containsKey(option.key())) {
                String key = node.id() + "." + option.key();
                secrets.put(key, String.valueOf(options.get(option.key())));
                options.put(option.key(), Map.of(SECRET_KEY, key));
            }
        }
        String json;
        try {
            json = JSON.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "The options of node '" + node.id() + "' cannot be written as JSON: " + e.getOriginalMessage(), e);
        }
        var builder = NodeDescriptor.newBuilder()
                .setNodeId(node.id())
                .setKind(kind(node.kind()))
                .setComponent(node.type())
                .setPlugin(plugin(node.plugin()))
                .setParallelism(node.parallelism())
                .setBounded(node.boundedness() == Boundedness.BOUNDED)
                .setOptionsJson(json);
        node.inputTables().forEach(t -> builder.addInputTables(tables.id(t)));
        node.outputTables().forEach(t -> builder.addOutputTables(tables.id(t)));
        return builder.build();
    }

    private static org.devlive.pipeforge.engine.rpc.proto.NodeKind kind(NodeKind kind) {
        return switch (kind) {
            case SOURCE -> org.devlive.pipeforge.engine.rpc.proto.NodeKind.NODE_KIND_SOURCE;
            case TRANSFORM -> org.devlive.pipeforge.engine.rpc.proto.NodeKind.NODE_KIND_TRANSFORM;
            case SINK -> org.devlive.pipeforge.engine.rpc.proto.NodeKind.NODE_KIND_SINK;
        };
    }

    static PluginRef plugin(String coordinate) {
        int at = coordinate.lastIndexOf('@');
        return at < 0
                ? PluginRef.newBuilder().setId(coordinate).build()
                : PluginRef.newBuilder()
                        .setId(coordinate.substring(0, at))
                        .setVersion(coordinate.substring(at + 1))
                        .build();
    }

    /** The tables of a chain, each once, numbered in the order they first appear. */
    private static final class TableDirectory {
        private final Map<CatalogTable, Integer> ids = new LinkedHashMap<>();

        int id(CatalogTable table) {
            return ids.computeIfAbsent(table, t -> ids.size());
        }

        List<TableEntry> entries() {
            List<TableEntry> entries = new ArrayList<>();
            ids.forEach((table, id) -> entries.add(table(id, table)));
            return entries;
        }
    }

    private static TableEntry table(int id, CatalogTable table) {
        TableId name = table.id();
        var builder = TableEntry.newBuilder()
                .setTableId(id)
                .setCatalog(text(name.catalog()))
                .setDatabase(text(name.database()))
                .setSchema(text(name.schema()))
                .setTable(name.table())
                .addAllPrimaryKey(table.primaryKey())
                .addAllPartitionKeys(table.partitionKeys())
                .putAllOptions(table.options())
                .setComment(text(table.comment()));
        for (Column column : table.columns()) {
            var entry = ColumnEntry.newBuilder()
                    .setName(column.name())
                    .setType(column.type().toString())
                    .setComment(text(column.comment()))
                    .putAllOptions(column.options());
            if (column.defaultValue() != null) {
                try {
                    entry.setDefaultValueJson(JSON.writeValueAsString(column.defaultValue()));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException(
                            "The default of column '" + column.name() + "' cannot be written as JSON", e);
                }
            }
            builder.addColumns(entry);
        }
        return builder.build();
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------------------------------- decode

    /**
     * Reads a description, puts the sensitive values back and resolves the components.
     *
     * @throws IllegalArgumentException if the bytes are not a description of a supported version,
     *     are damaged, or name a sensitive value that {@code secrets} does not hold
     */
    public static Decoded decode(byte[] descriptor, Map<String, String> secrets, ComponentResolver resolver) {
        var chain = unwrap(descriptor);
        Map<Integer, CatalogTable> tables = new LinkedHashMap<>();
        for (TableEntry entry : chain.getTablesList()) {
            tables.put(entry.getTableId(), table(entry));
        }
        var job = chain.getJob();
        List<PlannedNode> nodes = new ArrayList<>();
        String previous = null;
        for (NodeDescriptor node : chain.getNodesList()) {
            nodes.add(node(node, previous, tables, secrets, resolver));
            previous = node.getNodeId();
        }
        ChainDescriptor decoded = new ChainDescriptor(
                chain.getVertexId(),
                new JobContext(job.getJobName(), job.getParallelism(), ZoneId.of(job.getTimeZone())),
                nodes);
        RunContext run = new RunContext(
                job.getJobId(),
                job.getRunId(),
                job.getVariablesMap(),
                Instant.ofEpochMilli(job.getRunStartMs()),
                job.getScheduledTimeMs() == 0 ? null : Instant.ofEpochMilli(job.getScheduledTimeMs()),
                job.getMode(),
                job.getDelivery());
        return new Decoded(decoded, run);
    }

    private static PlannedNode node(
            NodeDescriptor node,
            @Nullable String previous,
            Map<Integer, CatalogTable> tables,
            Map<String, String> secrets,
            ComponentResolver resolver) {
        NodeKind kind = kind(node.getKind());
        Map<String, Object> options;
        try {
            options = JSON.readValue(node.getOptionsJson(), OBJECT);
        } catch (IOException e) {
            throw new IllegalArgumentException("The options of node '" + node.getNodeId() + "' are not JSON", e);
        }
        options.replaceAll((key, value) -> secret(node.getNodeId(), key, value, secrets));
        String plugin = node.getPlugin().getVersion().isEmpty()
                ? node.getPlugin().getId()
                : node.getPlugin().getId() + "@" + node.getPlugin().getVersion();
        return new PlannedNode(
                node.getNodeId(),
                kind,
                node.getComponent(),
                plugin,
                previous == null ? List.of() : List.of(previous),
                previous != null,
                node.getParallelism(),
                kind == NodeKind.SOURCE ? (node.getBounded() ? Boundedness.BOUNDED : Boundedness.UNBOUNDED) : null,
                ReadonlyConfig.of(options),
                // where an option came from matters for explain on the master, not for running it
                List.of(),
                lookup(node.getInputTablesList(), tables, node.getNodeId()),
                lookup(node.getOutputTablesList(), tables, node.getNodeId()),
                resolver.resolve(kind, node.getComponent(), plugin));
    }

    private static @Nullable Object secret(
            String node, String key, @Nullable Object value, Map<String, String> secrets) {
        if (value instanceof Map<?, ?> map && map.size() == 1 && map.get(SECRET_KEY) instanceof String name) {
            String secret = secrets.get(name);
            if (secret == null) {
                throw new IllegalArgumentException(
                        "The sensitive option '" + key + "' of node '" + node + "' was not delivered");
            }
            return secret;
        }
        return value;
    }

    private static NodeKind kind(org.devlive.pipeforge.engine.rpc.proto.NodeKind kind) {
        return switch (kind) {
            case NODE_KIND_SOURCE -> NodeKind.SOURCE;
            case NODE_KIND_TRANSFORM -> NodeKind.TRANSFORM;
            case NODE_KIND_SINK -> NodeKind.SINK;
            case NODE_KIND_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalArgumentException("Unknown node kind " + kind);
        };
    }

    private static List<CatalogTable> lookup(Collection<Integer> ids, Map<Integer, CatalogTable> tables, String node) {
        List<CatalogTable> list = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            CatalogTable table = tables.get(id);
            if (table == null) {
                throw new IllegalArgumentException(
                        "Node '" + node + "' refers to table " + id + ", which is not listed");
            }
            list.add(table);
        }
        return list;
    }

    private static CatalogTable table(TableEntry entry) {
        List<Column> columns = new ArrayList<>();
        for (ColumnEntry column : entry.getColumnsList()) {
            Object defaultValue = null;
            if (!column.getDefaultValueJson().isEmpty()) {
                try {
                    defaultValue = JSON.readValue(column.getDefaultValueJson(), Object.class);
                } catch (IOException e) {
                    throw new IllegalArgumentException(
                            "The default of column '" + column.getName() + "' is not JSON", e);
                }
            }
            columns.add(new Column(
                    column.getName(),
                    DataTypes.parse(column.getType()),
                    blank(column.getComment()),
                    defaultValue,
                    column.getOptionsMap()));
        }
        return new CatalogTable(
                new TableId(
                        blank(entry.getCatalog()),
                        blank(entry.getDatabase()),
                        blank(entry.getSchema()),
                        entry.getTable()),
                columns,
                entry.getPrimaryKeyList(),
                entry.getPartitionKeysList(),
                entry.getOptionsMap(),
                blank(entry.getComment()));
    }

    private static @Nullable String blank(String value) {
        return value.isEmpty() ? null : value;
    }

    // ---------------------------------------------------------------------------------- envelope

    /** The envelope around a description: magic, format version, length, body, CRC32C of the body. */
    static byte[] wrap(org.devlive.pipeforge.engine.rpc.proto.ChainDescriptor chain) {
        byte[] body;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(chain.getSerializedSize());
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            // the same description gives the same bytes, so the golden files can be compared
            out.useDeterministicSerialization();
            chain.writeTo(out);
            out.flush();
            body = bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CRC32C crc = new CRC32C();
        crc.update(body);
        return ByteBuffer.allocate(HEADER_BYTES + body.length + TRAILER_BYTES)
                .putInt(MAGIC)
                .putInt(FORMAT_VERSION)
                .putInt(body.length)
                .put(body)
                .putInt((int) crc.getValue())
                .array();
    }

    /**
     * The description inside an envelope.
     *
     * @throws IllegalArgumentException if the envelope is not a description, has a newer format
     *     version, is truncated or damaged
     */
    static org.devlive.pipeforge.engine.rpc.proto.ChainDescriptor unwrap(byte[] bytes) {
        if (bytes.length < HEADER_BYTES + TRAILER_BYTES) {
            throw new IllegalArgumentException("A task description needs at least 16 bytes, not " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Not a task description: magic " + Integer.toHexString(magic));
        }
        int version = buffer.getInt();
        if (version < 1 || version > FORMAT_VERSION) {
            throw new IllegalArgumentException("Task description format " + version
                    + " is not supported; this engine reads 1 to " + FORMAT_VERSION);
        }
        int length = buffer.getInt();
        if (length < 0 || length != bytes.length - HEADER_BYTES - TRAILER_BYTES) {
            throw new IllegalArgumentException(
                    "Task description length " + length + " does not match its " + bytes.length + " bytes");
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, HEADER_BYTES, length);
        int expected =
                ByteBuffer.wrap(bytes, HEADER_BYTES + length, TRAILER_BYTES).getInt();
        if ((int) crc.getValue() != expected) {
            throw new IllegalArgumentException("Task description is damaged: checksum mismatch");
        }
        try {
            return org.devlive.pipeforge.engine.rpc.proto.ChainDescriptor.parseFrom(
                    ByteBuffer.wrap(bytes, HEADER_BYTES, length));
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Task description is damaged: " + e.getMessage(), e);
        }
    }
}
