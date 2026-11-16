/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
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
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * Writes the rows of one table into a table of the database, at least once, in batches. Plugins
 * create it with their dialect from the options of {@link JdbcOptions#sinkRule} and the input
 * tables of the node.
 */
public class JdbcSink implements Sink<Void, Void> {

    private static final long serialVersionUID = 1L;

    private final JdbcSinkConfig config;
    private final JdbcDialect dialect;
    /** The input tables in a form that serializes, as an engine may ship the sink. */
    private final ArrayList<Input> inputs;

    /** A table as names and type strings. */
    private record Input(String table, ArrayList<String> names, ArrayList<String> types) implements Serializable {
        private static final long serialVersionUID = 1L;

        static Input of(CatalogTable table) {
            ArrayList<String> names = new ArrayList<>();
            ArrayList<String> types = new ArrayList<>();
            for (Column column : table.columns()) {
                names.add(column.name());
                types.add(column.type().toString());
            }
            return new Input(table.id().toQualifiedName(), names, types);
        }

        CatalogTable catalogTable() {
            List<Column> columns = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                columns.add(Column.of(names.get(i), DataTypes.parse(types.get(i))));
            }
            return CatalogTable.of(TableId.parse(table), columns);
        }
    }

    public JdbcSink(JdbcSinkConfig config, JdbcDialect dialect, List<CatalogTable> inputs) {
        this.config = config;
        this.dialect = dialect;
        this.inputs = new ArrayList<>(inputs.stream().map(Input::of).toList());
    }

    /**
     * Checks that one table comes in and that the target table has each of its columns; connects
     * while planning.
     */
    @Override
    public void validate(List<CatalogTable> tables, SinkContext context) {
        CatalogTable input = input(tables);
        try (Connection connection =
                JdbcConnections.open(config.connection(), dialect, context.classLoader(), config.node())) {
            target(connection, input);
        } catch (SQLException e) {
            throw JdbcErrors.of(e, dialect, config.connection(), false, config.table(), config.node(), false);
        }
    }

    private CatalogTable input(List<CatalogTable> tables) {
        if (tables.size() != 1) {
            throw PipeForgeException.of(ErrorCode.TOPOLOGY_UNSUPPORTED)
                    .param("node", config.node())
                    .param(
                            "reason",
                            "the JDBC sink writes one table, but " + tables.size()
                                    + " come in; give each table a sink of its own")
                    .scope(ErrorScope.node(config.node()))
                    .build();
        }
        return tables.get(0);
    }

    /**
     * The insert into the target: the target's names of the input columns, in input order.
     *
     * @throws PipeForgeException {@code PF-META-0001} without the table, {@code PF-META-0002} for an
     *     input column the target lacks
     */
    Target target(Connection connection, CatalogTable input) throws SQLException {
        JdbcSchemas.Described target = JdbcSchemas.describe(
                connection, dialect, config.table(), config.connection().target());
        List<String> columns = new ArrayList<>();
        for (Column column : input.columns()) {
            Column match = target.table().columns().stream()
                    .filter(c -> c.name().equals(column.name()))
                    .findFirst()
                    .or(() -> target.table().columns().stream()
                            .filter(c -> c.name().equalsIgnoreCase(column.name()))
                            .findFirst())
                    .orElseThrow(() -> PipeForgeException.of(ErrorCode.COLUMN_NOT_FOUND)
                            .param("table", config.table().toQualifiedName())
                            .param("column", column.name())
                            .scope(ErrorScope.node(config.node()))
                            .build());
            columns.add(match.name());
        }
        return new Target(dialect.insertStatement(target.physical(), columns), input);
    }

    /** The insert statement and the table whose rows it binds. */
    record Target(String insert, CatalogTable input) {}

    @Override
    public SinkWriter<Void, Void> createWriter(WriterContext context, List<Void> restored) {
        return new JdbcBatchWriter(
                this, context, input(inputs.stream().map(Input::catalogTable).toList()));
    }

    JdbcSinkConfig config() {
        return config;
    }

    JdbcDialect dialect() {
        return dialect;
    }

    @Override
    public VersionedSerializer<Void> committableSerializer() {
        return Nothing.INSTANCE;
    }

    @Override
    public VersionedSerializer<Void> writerStateSerializer() {
        return Nothing.INSTANCE;
    }

    /** The JDBC sink has no committables and no writer state. */
    static final class Nothing implements VersionedSerializer<Void> {
        static final Nothing INSTANCE = new Nothing();

        @Override
        public int version() {
            return 1;
        }

        @Override
        public byte[] serialize(@Nullable Void value) {
            return new byte[0];
        }

        @Override
        public @Nullable Void deserialize(int version, byte[] bytes) {
            return null;
        }
    }
}
