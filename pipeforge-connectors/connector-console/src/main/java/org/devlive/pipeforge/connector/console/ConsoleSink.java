/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkContext;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;

/**
 * Prints rows; accepts every table, type and row kind, and keeps no state. The input tables are
 * kept as text (name, column names and type texts), so that the sink stays serializable and every
 * writer prints the same headers wherever it runs.
 */
final class ConsoleSink implements Sink<Void, Void> {

    private static final long serialVersionUID = 1L;

    /** A table as text. */
    record Schema(String table, List<String> names, List<String> types) implements Serializable {
        private static final long serialVersionUID = 1L;

        Schema {
            names = List.copyOf(names);
            types = List.copyOf(types);
        }

        static Schema of(CatalogTable table) {
            return new Schema(
                    table.id().toQualifiedName(),
                    table.columns().stream().map(Column::name).toList(),
                    table.columns().stream().map(c -> c.type().toString()).toList());
        }

        CatalogTable toTable() {
            List<Column> columns = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                columns.add(Column.of(names.get(i), DataTypes.parse(types.get(i))));
            }
            return CatalogTable.of(TableId.parse(table), columns);
        }
    }

    private final ConsoleSettings settings;
    private final ArrayList<Schema> inputs;

    ConsoleSink(ConsoleSettings settings, List<CatalogTable> inputs) {
        this.settings = settings;
        this.inputs = new ArrayList<>(inputs.stream().map(Schema::of).toList());
    }

    @Override
    public void validate(List<CatalogTable> inputs, SinkContext context) {}

    @Override
    public SinkWriter<Void, Void> createWriter(WriterContext context, List<Void> restored) {
        return new ConsoleWriter(
                settings,
                inputs.stream().map(Schema::toTable).toList(),
                new Values(context.job().timeZone()),
                ConsoleOutput.stream(settings.target()),
                context.subtaskIndex(),
                context.parallelism());
    }

    @Override
    public VersionedSerializer<Void> committableSerializer() {
        return NothingSerializer.INSTANCE;
    }

    @Override
    public VersionedSerializer<Void> writerStateSerializer() {
        return NothingSerializer.INSTANCE;
    }

    ConsoleSettings settings() {
        return settings;
    }

    /** The sink has neither committables nor state; nothing is ever serialized. */
    enum NothingSerializer implements VersionedSerializer<Void> {
        INSTANCE;

        @Override
        public int version() {
            return 1;
        }

        @Override
        public byte[] serialize(Void value) {
            return new byte[0];
        }

        @Override
        public Void deserialize(int version, byte[] bytes) {
            return null;
        }
    }
}
