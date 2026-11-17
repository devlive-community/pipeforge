/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.preview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkContext;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;

/**
 * The sink of a preview ({@code type: pipeforge-preview}, built in): keeps the first rows of every
 * table in a buffer of the server, and writes nowhere.
 */
public final class PreviewSink {

    /** The type of the sink in a rewritten job. */
    public static final String TYPE = "pipeforge-preview";

    static final Option<String> BUFFER = Options.key("buffer").stringType().noDefault();

    private static final Map<String, Buffer> BUFFERS = new ConcurrentHashMap<>();

    private PreviewSink() {}

    /** The rows of one preview, at most {@code limit} per table. */
    static final class Buffer {
        final String id = UUID.randomUUID().toString();
        final int limit;
        private final Map<TableId, List<Row>> rows = new LinkedHashMap<>();
        private final Map<TableId, Boolean> truncated = new LinkedHashMap<>();

        Buffer(int limit) {
            this.limit = limit;
        }

        synchronized void add(Row row) {
            List<Row> table = rows.computeIfAbsent(row.table(), t -> new ArrayList<>());
            if (table.size() < limit) {
                table.add(row);
            } else {
                truncated.put(row.table(), true);
            }
        }

        /** Whether some table has {@code limit} rows; a preview stops there. */
        synchronized boolean full() {
            return rows.values().stream().anyMatch(r -> r.size() >= limit);
        }

        synchronized Map<TableId, List<Row>> rows() {
            Map<TableId, List<Row>> copy = new LinkedHashMap<>();
            rows.forEach((table, list) -> copy.put(table, List.copyOf(list)));
            return copy;
        }

        synchronized boolean truncated(TableId table) {
            return truncated.getOrDefault(table, false);
        }
    }

    static Buffer open(int limit) {
        Buffer buffer = new Buffer(limit);
        BUFFERS.put(buffer.id, buffer);
        return buffer;
    }

    static void close(Buffer buffer) {
        BUFFERS.remove(buffer.id);
    }

    /** The factory the preview's components resolve the sink to. */
    public static final class Factory implements SinkFactory {
        @Override
        public String identifier() {
            return TYPE;
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder().required(BUFFER).build();
        }

        @Override
        public Sink<?, ?> createSink(FactoryContext context) {
            return new Instance(context.options().get(BUFFER));
        }
    }

    private static final class Instance implements Sink<String, String> {
        private static final long serialVersionUID = 1L;
        private final String buffer;

        Instance(String buffer) {
            this.buffer = buffer;
        }

        @Override
        public void validate(List<CatalogTable> inputs, SinkContext context) {}

        @Override
        public SinkWriter<String, String> createWriter(WriterContext context, List<String> restored) {
            Buffer target = BUFFERS.get(buffer);
            return new SinkWriter<>() {
                @Override
                public void write(Row row) {
                    if (target != null) {
                        target.add(row);
                    }
                }

                @Override
                public void applySchemaChange(SchemaChangeEvent event) {}

                @Override
                public void flush() {}
            };
        }

        @Override
        public VersionedSerializer<String> committableSerializer() {
            return Strings.INSTANCE;
        }

        @Override
        public VersionedSerializer<String> writerStateSerializer() {
            return Strings.INSTANCE;
        }
    }

    /** Writes nothing worth keeping: a preview has no commits and no state. */
    private enum Strings implements VersionedSerializer<String> {
        INSTANCE;

        @Override
        public int version() {
            return 1;
        }

        @Override
        public byte[] serialize(String value) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(int version, byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
