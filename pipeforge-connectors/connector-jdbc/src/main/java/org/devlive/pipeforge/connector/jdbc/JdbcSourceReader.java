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
 *
 * <p>A range of a table is read in the order of its split column, and a poll ends only where the
 * column's value changes: the reader reads one row ahead and keeps it for the next poll. A snapshot
 * therefore falls between two values, and the split resumes after the last value it emitted
 * (design 06 section 5). A value with more rows than {@code resume.max-group-rows} lets a poll end
 * inside its rows; a snapshot then resumes before that value, and its rows read so far are read
 * again. Whole tables, queries and NULLs resume from their start.
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

    /** The position of the split column among the columns read; -1 when the split is not ordered. */
    private int keyColumn = -1;
    /** A row read but not emitted yet: the first of the next value. */
    private Object @Nullable [] ahead;
    /** The value of the split column of the last row emitted. */
    private @Nullable Long lastKey;
    /** The last value whose rows were all emitted. */
    private @Nullable Long completeKey;
    /** The rows of {@link #lastKey} emitted so far. */
    private int groupRows;
    /** Whether the last poll ended where the split column's value changes. */
    private boolean atBoundary;

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
            atBoundary = false;
            int emitted = 0;
            while (true) {
                Object[] values = ahead;
                ahead = null;
                if (values == null) {
                    if (!result.next()) {
                        JdbcSplit done = current;
                        closeSplit();
                        if (done != null) {
                            context.splitFinished(done.splitId());
                        }
                        request();
                        return PollStatus.MORE_AVAILABLE;
                    }
                    values = new Object[reading.types().size()];
                    for (int c = 0; c < values.length; c++) {
                        values[c] = dialect.read(result, c + 1, reading.types().get(c));
                    }
                }
                if (keyColumn >= 0) {
                    long key = ((Number) java.util.Objects.requireNonNull(values[keyColumn])).longValue();
                    boolean same = lastKey != null && lastKey == key;
                    if (emitted >= ROWS_PER_POLL && !same) {
                        ahead = values;
                        atBoundary = true;
                        return PollStatus.MORE_AVAILABLE;
                    }
                    if (emitted >= ROWS_PER_POLL && groupRows >= config.maxGroupRows()) {
                        // too many rows of one value to keep together: a snapshot may fall among them
                        ahead = values;
                        return PollStatus.MORE_AVAILABLE;
                    }
                    if (same) {
                        groupRows++;
                    } else {
                        completeKey = lastKey;
                        lastKey = key;
                        groupRows = 1;
                    }
                } else if (emitted >= ROWS_PER_POLL) {
                    ahead = values;
                    return PollStatus.MORE_AVAILABLE;
                }
                output.collect(GenericRow.of(reading.table().id(), values));
                emitted++;
            }
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
                                split.predicate(dialect))
                        + split.orderBy(dialect);
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
        keyColumn = split.ordered() ? indexOf(opened.table().columnNames(), split.column()) : -1;
        return result;
    }

    private static int indexOf(List<String> columns, @Nullable String column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
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
        keyColumn = -1;
        ahead = null;
        lastKey = null;
        completeKey = null;
        groupRows = 0;
        atBoundary = false;
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
        JdbcSplit reading = current;
        if (reading != null) {
            // between two values every row up to the last one emitted is out; inside a value only those before it
            Long watermark = keyColumn < 0 ? null : atBoundary ? lastKey : completeKey;
            unfinished.add(watermark == null ? reading : reading.resumedAfter(watermark));
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
