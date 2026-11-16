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
import java.util.List;
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
 * for the next split when one is done. One connection serves all splits of the reader.
 *
 * <p>A value the dialect cannot read makes that row a bad row; the reader goes on with the next.
 */
public final class JdbcSourceReader implements SourceReader<JdbcSplit> {

    /** Rows emitted per poll, so that the task can take its mail between them. */
    static final int ROWS_PER_POLL = 256;

    private final JdbcSourceConfig config;
    private final JdbcDialect dialect;
    private final ReaderContext context;
    private final Deque<JdbcSplit> splits = new ArrayDeque<>();
    private boolean noMore;
    private boolean requested;
    private @Nullable Connection connection;
    private @Nullable CatalogTable table;
    private @Nullable TableId physical;
    private List<DataType> types = List.of();
    private volatile @Nullable PreparedStatement statement;
    private @Nullable ResultSet rows;
    private @Nullable JdbcSplit current;

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
            CatalogTable produced = described();
            for (int i = 0; i < ROWS_PER_POLL; i++) {
                if (!result.next()) {
                    closeSplit();
                    request();
                    return PollStatus.MORE_AVAILABLE;
                }
                Object[] values = new Object[types.size()];
                for (int c = 0; c < values.length; c++) {
                    values[c] = dialect.read(result, c + 1, types.get(c));
                }
                output.collect(GenericRow.of(produced.id(), values));
            }
            return PollStatus.MORE_AVAILABLE;
        } catch (SQLException e) {
            throw JdbcErrors.of(e, dialect, config.connection(), false, config.table(), config.node(), true);
        }
    }

    private ResultSet open(JdbcSplit split) throws SQLException {
        Connection open = connection();
        CatalogTable produced = described();
        String sql;
        if (config.query() != null) {
            sql = "SELECT * FROM (" + config.query() + ") pipeforge_query";
        } else {
            sql = dialect.selectQuery(physical(), produced.columnNames(), split.predicate(dialect));
        }
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
        return result;
    }

    private Connection connection() throws SQLException {
        Connection open = connection;
        if (open == null) {
            open = JdbcConnections.open(config.connection(), dialect, context.classLoader(), config.node());
            connection = open;
            if (config.query() != null) {
                table = JdbcSchemas.describeQuery(open, dialect, config.query(), config.produced());
            } else {
                JdbcSchemas.Described described = JdbcSchemas.describe(
                        open,
                        dialect,
                        java.util.Objects.requireNonNull(config.table()),
                        config.connection().target());
                table = described.table();
                physical = described.physical();
            }
            List<DataType> columnTypes = new ArrayList<>();
            described().columns().forEach(c -> columnTypes.add(c.type()));
            types = List.copyOf(columnTypes);
        }
        return open;
    }

    private CatalogTable described() {
        CatalogTable known = table;
        if (known == null) {
            throw new IllegalStateException("the table is described when the first split opens");
        }
        return known;
    }

    private TableId physical() {
        TableId known = physical;
        if (known == null) {
            throw new IllegalStateException("the table is described when the first split opens");
        }
        return known;
    }

    private void closeSplit() throws SQLException {
        ResultSet result = rows;
        PreparedStatement prepared = statement;
        rows = null;
        statement = null;
        current = null;
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
