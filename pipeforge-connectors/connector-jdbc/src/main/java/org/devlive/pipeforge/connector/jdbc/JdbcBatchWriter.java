/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.type.DataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects rows into batches and writes each batch in a transaction of its own.
 *
 * <ul>
 *   <li>A batch that fails for a transient reason (a broken connection, a deadlock, a timeout) is
 *       rolled back and tried again up to {@code max-retries} times, after a backoff that doubles;
 *       a broken connection is opened again.
 *   <li>A batch that fails because of its data is rolled back and split in halves, each written on
 *       its own, until the bad rows are single: each is reported to the dirty data policy with
 *       {@link WriterContext#reportBadRow}, and the good rows are written.
 *   <li>Anything else fails the task.
 * </ul>
 *
 * <p>Since a failed batch leaves no rows behind, the writer is at least once: rows are written
 * again only when a commit was lost.
 */
public final class JdbcBatchWriter implements SinkWriter<Void, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcBatchWriter.class);

    private final JdbcSink sink;
    private final JdbcSinkConfig config;
    private final JdbcDialect dialect;
    private final WriterContext context;
    private final CatalogTable input;
    private final List<DataType> types = new ArrayList<>();
    private final List<Row> batch = new ArrayList<>();
    private @Nullable Connection connection;
    private @Nullable PreparedStatement insert;
    private long retries;
    private long splits;

    JdbcBatchWriter(JdbcSink sink, WriterContext context, CatalogTable input) {
        this.sink = sink;
        this.config = sink.config();
        this.dialect = sink.dialect();
        this.context = context;
        this.input = input;
        input.columns().forEach(c -> types.add(c.type()));
    }

    @Override
    public void write(Row row) {
        batch.add(row);
        if (batch.size() >= config.batchSize()) {
            flush();
        }
    }

    @Override
    public void flush() {
        if (batch.isEmpty()) {
            return;
        }
        List<Row> rows = List.copyOf(batch);
        batch.clear();
        write(rows);
    }

    /** Writes {@code rows}, retrying and splitting as the class describes. */
    private void write(List<Row> rows) {
        for (int attempt = 0; ; attempt++) {
            try {
                execute(rows);
                return;
            } catch (SQLException e) {
                rollback();
                JdbcErrorKind kind = dialect.classify(e);
                if (kind == JdbcErrorKind.DATA) {
                    bisect(rows, e);
                    return;
                }
                boolean retry = (kind == JdbcErrorKind.TRANSIENT || kind == JdbcErrorKind.CONNECTION)
                        && attempt < config.maxRetries();
                if (!retry) {
                    throw JdbcErrors.of(e, dialect, config.connection(), false, config.table(), config.node(), false);
                }
                if (kind == JdbcErrorKind.CONNECTION) {
                    closeQuietly();
                }
                retries++;
                long wait = config.retryBackoff().toMillis() << Math.min(attempt, 20);
                LOG.warn(
                        "{}: a batch of {} rows failed, retry {} of {} in {} ms: {}",
                        config.node(),
                        rows.size(),
                        attempt + 1,
                        config.maxRetries(),
                        wait,
                        JdbcErrors.reason(e));
                sleep(wait);
            }
        }
    }

    /** A batch with bad rows: halves until the bad rows are single, each reported. */
    private void bisect(List<Row> rows, SQLException error) {
        if (rows.size() == 1) {
            context.reportBadRow(rows.get(0), JdbcErrors.reason(error));
            return;
        }
        splits++;
        int half = rows.size() / 2;
        write(rows.subList(0, half));
        write(rows.subList(half, rows.size()));
    }

    private void execute(List<Row> rows) throws SQLException {
        Connection open = connection();
        PreparedStatement statement = insert;
        if (statement == null) {
            statement = open.prepareStatement(sink.target(open, input).insert());
            insert = statement;
        }
        for (Row row : rows) {
            for (int i = 0; i < types.size(); i++) {
                dialect.write(statement, i + 1, row.get(i), types.get(i));
            }
            statement.addBatch();
        }
        statement.executeBatch();
        open.commit();
    }

    private Connection connection() throws SQLException {
        Connection open = connection;
        if (open == null) {
            open = JdbcConnections.open(config.connection(), dialect, context.classLoader(), config.node());
            open.setAutoCommit(false);
            connection = open;
        }
        return open;
    }

    private void rollback() {
        Connection open = connection;
        PreparedStatement statement = insert;
        try {
            if (statement != null) {
                statement.clearBatch();
            }
            if (open != null) {
                open.rollback();
            }
        } catch (SQLException e) {
            // a broken connection: it is opened again for the retry
            closeQuietly();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry a batch", e);
        }
    }

    /** Batches tried again after a transient failure. */
    long retries() {
        return retries;
    }

    /** Failed batches split in halves to find bad rows. */
    long splits() {
        return splits;
    }

    /** A failed batch is rolled back: it leaves no rows behind and can be split to find bad rows. */
    @Override
    public boolean failedWritesLeaveNoRows() {
        return true;
    }

    private void closeQuietly() {
        try {
            close();
        } catch (SQLException e) {
            LOG.debug("{}: closing a broken connection failed", config.node(), e);
        }
    }

    @Override
    public void close() throws SQLException {
        PreparedStatement statement = insert;
        Connection open = connection;
        insert = null;
        connection = null;
        try {
            if (statement != null) {
                statement.close();
            }
        } finally {
            if (open != null) {
                open.close();
            }
        }
    }
}
