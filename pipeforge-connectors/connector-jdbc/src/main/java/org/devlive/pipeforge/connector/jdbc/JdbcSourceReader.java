/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.type.DataType;
import org.jspecify.annotations.Nullable;

/**
 * Reads the splits it gets one after another, streaming each result with the fetch size, and asks
 * for the next split when one is done. One connection serves all splits of the reader; each table
 * is described once, when its first split opens.
 *
 * <p>A value the dialect cannot read makes that row a bad row; the reader goes on with the next.
 */
public final class JdbcSourceReader implements SourceReader<JdbcSplit> {

    /** Rows emitted per poll, so that the task can take its mail between them. */
    static final int ROWS_PER_POLL = 256;

    /** A table as the reader reads it: its produced schema, its name in SQL and its column types. */
    private record Target(CatalogTable table, @Nullable TableId physical, List<DataType> types) {
        static Target of(CatalogTable table, @Nullable TableId physical) {
            List<DataType> types = new ArrayList<>();
            table.columns().forEach(c -> types.add(c.type()));
            return new Target(table, physical, List.copyOf(types));
        }
    }

    private final JdbcSourceConfig config;
    private final JdbcDialect dialect;
    private final ReaderContext context;
    private final Deque<JdbcSplit> splits = new ArrayDeque<>();
    private final Map<TableId, Target> tables = new HashMap<>();
    private boolean noMore;
    private boolean requested;
    private @Nullable Connection connection;
    private @Nullable Target query;
    private volatile @Nullable PreparedStatement statement;
    private @Nullable ResultSet rows;
    private @Nullable JdbcSplit current;
    private @Nullable Target target;

    JdbcSourceReader(JdbcSourceConfig config, JdbcDialect dialect, ReaderContext context) {
        this.config = config;
        this.dialect = dialect;
        this.context = context;
    }

    @Override
    public void start() {
        request();
    }

    private void request() {
        if (!requested) {
            requested = true;
            context.sendSplitRequest();
        }
    }

    @Override
    public void addSplits(List<JdbcSplit> added) {
        splits.addAll(added);
        requested = false;
    }

    @Override
    public void notifyNoMoreSplits() {
        noMore = true;
    }

    @Override
    public PollStatus pollNext(ReaderOutput output) {
        try {
            ResultSet result = rows;
            if (result == null) {
                JdbcSplit next = splits.poll();
                if (next == null) {
                    if (noMore) {
                        return PollStatus.END_OF_INPUT;
                    }
                    request();
                    return PollStatus.NOTHING_AVAILABLE;
                }
                result = open(next);
            }
            Target reading = java.util.Objects.requireNonNull(target);
            for (int i = 0; i < ROWS_PER_POLL; i++) {
                if (!result.next()) {
                    JdbcSplit done = current;
                    closeSplit();
                    if (done != null) {
                        context.splitFinished(done.splitId());
                    }
                    request();
                    return PollStatus.MORE_AVAILABLE;
                }
                Object[] values = new Object[reading.types().size()];
                for (int c = 0; c < values.length; c++) {
                    values[c] = dialect.read(result, c + 1, reading.types().get(c));
                }
                output.collect(GenericRow.of(reading.table().id(), values));
            }
            return PollStatus.MORE_AVAILABLE;
        } catch (SQLException e) {
            JdbcSplit split = current;
            TableId table = split != null && split.table() != null ? split.table() : config.table();
            throw JdbcErrors.of(e, dialect, config.connection(), false, table, config.node(), true);
        }
    }

    private ResultSet open(JdbcSplit split) throws SQLException {
        Connection open = connection();
        Target opened = target(open, split);
        String sql = split.table() == null
                ? "SELECT * FROM (" + config.query() + ") pipeforge_query"
                : dialect.selectQuery(
                        java.util.Objects.requireNonNull(opened.physical()),
                        opened.table().columnNames(),
                        split.predicate(dialect));
        PreparedStatement prepared =
                open.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement = prepared;
        dialect.prepareStreaming(open, prepared, config.fetchSize());
        List<Long> parameters = split.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            prepared.setLong(i + 1, parameters.get(i));
        }
        ResultSet result = prepared.executeQuery();
        rows = result;
        current = split;
        target = opened;
        return result;
    }

    /** The table of a split, described when its first split opens; the query when it has none. */
    private Target target(Connection open, JdbcSplit split) throws SQLException {
        TableId table = split.table();
        if (table == null) {
            Target described = query;
            if (described == null) {
                described = Target.of(
                        JdbcSchemas.describeQuery(
                                open, dialect, java.util.Objects.requireNonNull(config.query()), config.produced()),
                        null);
                query = described;
            }
            return described;
        }
        Target described = tables.get(table);
        if (described == null) {
            JdbcSchemas.Described found = JdbcSchemas.describe(
                    open, dialect, table, config.connection().target());
            described = Target.of(found.table(), found.physical());
            tables.put(table, described);
        }
        return described;
    }

    private Connection connection() {
        Connection open = connection;
        if (open == null) {
            open = JdbcConnections.open(config.connection(), dialect, context.classLoader(), config.node());
            connection = open;
        }
        return open;
    }

    private void closeSplit() throws SQLException {
        ResultSet result = rows;
        PreparedStatement prepared = statement;
        rows = null;
        statement = null;
        current = null;
        target = null;
        try {
            if (result != null) {
                result.close();
            }
        } finally {
            if (prepared != null) {
                prepared.close();
            }
        }
    }

    @Override
    public List<JdbcSplit> snapshotState(long checkpointId) {
        List<JdbcSplit> unfinished = new ArrayList<>();
        if (current != null) {
            unfinished.add(current);
        }
        unfinished.addAll(splits);
        return unfinished;
    }

    @Override
    public void cancel() {
        PreparedStatement running = statement;
        if (running != null) {
            try {
                running.cancel();
            } catch (SQLException e) {
                // the task is stopping; the interrupt that follows is the fallback
            }
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            closeSplit();
        } finally {
            Connection open = connection;
            connection = null;
            if (open != null) {
                open.close();
            }
        }
    }
}
