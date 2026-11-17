/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the splits of a JDBC source when it starts, on the job master, and hands them out one at a
 * time to the readers that ask, so that fast readers read more.
 *
 * <p>Each table is split on its own; with {@code tables.include} the tables are found when the
 * enumerator starts, and the splits of all of them share one queue. With {@code split.mode: range}
 * the split column is the configured one, else the primary key when it is a single integer column.
 * Its MIN and MAX are probed and cut into ranges; without such a column a table is read in one
 * split, as is a query.
 */
public final class JdbcSplitEnumerator implements SplitEnumerator<JdbcSplit, List<JdbcSplit>> {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcSplitEnumerator.class);

    private final JdbcSourceConfig config;
    private final JdbcDialect dialect;
    private final EnumeratorContext<JdbcSplit> context;
    private final ClassLoader loader;
    private final Deque<JdbcSplit> remaining = new ArrayDeque<>();
    private boolean started;
    /** The splits made at the start, or -1 when restored: some of them were read before. */
    private int total = -1;

    JdbcSplitEnumerator(
            JdbcSourceConfig config,
            JdbcDialect dialect,
            EnumeratorContext<JdbcSplit> context,
            ClassLoader loader,
            @Nullable List<JdbcSplit> restored) {
        this.config = config;
        this.dialect = dialect;
        this.context = context;
        this.loader = loader;
        if (restored != null) {
            remaining.addAll(restored);
            started = true;
        }
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        started = true;
        remaining.addAll(splits());
        total = remaining.size();
        LOG.info("{} reads {} in {} split(s)", config.node(), config.produced(), remaining.size());
    }

    @Override
    public java.util.OptionalInt totalSplits() {
        return total < 0 ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(total);
    }

    List<JdbcSplit> splits() {
        if (config.query() != null) {
            return List.of(JdbcSplit.all(null));
        }
        try (Connection connection = JdbcConnections.open(config.connection(), dialect, loader, config.node())) {
            List<TableId> tables = config.manyTables()
                    ? JdbcTables.discover(connection, dialect, config.include(), config.exclude())
                    : List.of(java.util.Objects.requireNonNull(config.table()));
            List<JdbcSplit> splits = new ArrayList<>();
            for (TableId table : tables) {
                splits.addAll(splits(connection, table));
            }
            return splits;
        } catch (SQLException e) {
            throw JdbcErrors.of(e, dialect, config.connection(), false, config.table(), config.node(), true);
        }
    }

    /** The splits of one table. */
    private List<JdbcSplit> splits(Connection connection, TableId table) throws SQLException {
        if (config.splitMode() == JdbcSplitMode.NONE) {
            return List.of(JdbcSplit.all(table));
        }
        JdbcSchemas.Described described = JdbcSchemas.describe(
                connection, dialect, table, config.connection().target());
        Column column = splitColumn(described.table());
        if (column == null) {
            return List.of(JdbcSplit.all(table));
        }
        Long min = null;
        Long max = null;
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(dialect.minMaxQuery(described.physical(), column.name()))) {
            if (rs.next()) {
                long low = rs.getLong(1);
                min = rs.wasNull() ? null : low;
                long high = rs.getLong(2);
                max = rs.wasNull() ? null : high;
            }
        }
        int count = config.splitCount() > 0 ? config.splitCount() : 4 * context.parallelism();
        return JdbcSplitter.ranges(
                table, column.name(), min, max, count, column.type().isNullable());
    }

    /** The split column: the configured one, else a primary key of one integer column. */
    private @Nullable Column splitColumn(CatalogTable table) {
        String name = config.splitColumn();
        if (name != null) {
            Column column = table.columns().stream()
                    .filter(c -> c.name().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> PipeForgeException.of(ErrorCode.COLUMN_NOT_FOUND)
                            .param("table", table.id().toQualifiedName())
                            .param("column", name)
                            .build());
            if (!integral(column.type())) {
                throw PipeForgeException.of(ErrorCode.OPTION_INVALID)
                        .param("option", JdbcOptions.SPLIT_COLUMN.key())
                        .param("node", config.node())
                        .param("value", name)
                        .param("reason", "the column is " + column.type() + ", not an integer")
                        .build();
            }
            return column;
        }
        if (table.primaryKey().size() != 1) {
            return null;
        }
        Column key = table.column(table.primaryKey().get(0)).orElse(null);
        return key != null && integral(key.type()) ? key : null;
    }

    static boolean integral(DataType type) {
        return switch (type.root()) {
            case INT8, INT16, INT32, INT64 -> true;
            case DECIMAL -> type.scale() == 0 && type.precision() <= 18;
            default -> false;
        };
    }

    @Override
    public void handleSplitRequest(int readerId) {
        JdbcSplit next = remaining.poll();
        if (next == null) {
            context.signalNoMoreSplits(readerId);
        } else {
            context.assignSplit(next, readerId);
        }
    }

    @Override
    public void addSplitsBack(List<JdbcSplit> splits, int readerId) {
        splits.forEach(remaining::addFirst);
    }

    @Override
    public void addReader(int readerId) {
        // readers ask for splits themselves
    }

    @Override
    public List<JdbcSplit> snapshotState(long checkpointId) {
        return List.copyOf(remaining);
    }
}
