/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.devlive.pipeforge.api.capability.SupportsMultiTable;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceContext;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * A bounded JDBC source of one table, of the tables {@code tables.include} selects, or of a query. Plugins create it with their dialect from the
 * options of {@link JdbcOptions#sourceRule}.
 */
public class JdbcSource implements Source<JdbcSplit, List<JdbcSplit>>, SupportsMultiTable {

    private static final long serialVersionUID = 1L;

    private final JdbcSourceConfig config;
    private final JdbcDialect dialect;

    public JdbcSource(JdbcSourceConfig config, JdbcDialect dialect) {
        this.config = config;
        this.dialect = dialect;
    }

    public JdbcSourceConfig config() {
        return config;
    }

    @Override
    public Boundedness boundedness() {
        return Boundedness.BOUNDED;
    }

    /** The table or the result of the query, from the database's metadata; connects while planning. */
    @Override
    public List<CatalogTable> producedTables(SourceContext context) {
        try (Connection connection =
                JdbcConnections.open(config.connection(), dialect, context.classLoader(), config.node())) {
            if (config.query() != null) {
                return List.of(JdbcSchemas.describeQuery(connection, dialect, config.query(), config.produced()));
            }
            List<TableId> tables = config.manyTables()
                    ? JdbcTables.discover(connection, dialect, config.include(), config.exclude())
                    : List.of(java.util.Objects.requireNonNull(config.table()));
            if (tables.isEmpty()) {
                throw PipeForgeException.of(ErrorCode.OPTION_INVALID)
                        .param("option", JdbcOptions.TABLES_INCLUDE.key())
                        .param("node", config.node())
                        .param("value", String.join(", ", config.include()))
                        .param("reason", "no table of " + config.connection().target() + " matches")
                        .build();
            }
            List<CatalogTable> produced = new java.util.ArrayList<>();
            for (TableId table : tables) {
                produced.add(JdbcSchemas.describe(
                                connection, dialect, table, config.connection().target())
                        .table());
            }
            return produced;
        } catch (SQLException e) {
            throw JdbcErrors.of(e, dialect, config.connection(), false, config.table(), config.node(), true);
        }
    }

    @Override
    public SplitEnumerator<JdbcSplit, List<JdbcSplit>> createEnumerator(EnumeratorContext<JdbcSplit> context) {
        return new JdbcSplitEnumerator(config, dialect, context, getClass().getClassLoader(), null);
    }

    @Override
    public SplitEnumerator<JdbcSplit, List<JdbcSplit>> restoreEnumerator(
            EnumeratorContext<JdbcSplit> context, List<JdbcSplit> state) {
        return new JdbcSplitEnumerator(config, dialect, context, getClass().getClassLoader(), state);
    }

    @Override
    public SourceReader<JdbcSplit> createReader(ReaderContext context) {
        return new JdbcSourceReader(config, dialect, context);
    }

    @Override
    public VersionedSerializer<JdbcSplit> splitSerializer() {
        return JdbcSplit.Serializer.INSTANCE;
    }

    @Override
    public VersionedSerializer<List<JdbcSplit>> enumeratorStateSerializer() {
        return JdbcSplit.ListSerializer.INSTANCE;
    }
}
