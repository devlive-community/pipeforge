/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.checkpoint;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.CheckpointType;
import org.jspecify.annotations.Nullable;

/**
 * The checkpoints of runs in the metastore the server migrates (expand migration 3). Plain JDBC,
 * the same statements on H2, MySQL and PostgreSQL. Failures are {@code PF-CLUS-0013}.
 */
public final class JdbcCheckpointStore implements CheckpointStore {

    private final DataSource dataSource;
    private final String url;

    /**
     * @param url the metastore's URL without credentials, for messages
     */
    public JdbcCheckpointStore(DataSource dataSource, String url) {
        this.dataSource = dataSource;
        this.url = url;
    }

    /**
     * Checks that the metastore has the checkpoint table, which a server of this version creates.
     *
     * @throws PipeForgeException {@code PF-CLUS-0013} otherwise
     */
    public void check() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT COUNT(*) FROM checkpoint WHERE checkpoint_id < 0");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
        } catch (SQLException e) {
            throw unavailable(
                    "it has no checkpoint table; start a server of this version on it first, which migrates it ("
                            + e.getMessage() + ")",
                    e);
        }
    }

    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T run(String what, Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw unavailable(what + ": " + e.getMessage(), e);
        }
    }

    @Override
    public long lastCheckpointId(String runId) {
        return run("reading the checkpoints of run " + runId, c -> {
            try (PreparedStatement statement =
                    c.prepareStatement("SELECT MAX(checkpoint_id) FROM checkpoint WHERE run_id = ?")) {
                statement.setString(1, runId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        });
    }

    @Override
    public void begin(Checkpoint checkpoint) {
        run("recording checkpoint " + checkpoint.checkpointId() + " of run " + checkpoint.runId(), c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT INTO checkpoint (run_id, checkpoint_id, job_name, attempt, type, status, trigger_reason,"
                            + " name, triggered_at, path) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, checkpoint.runId());
                statement.setLong(2, checkpoint.checkpointId());
                statement.setString(3, checkpoint.jobName());
                statement.setInt(4, checkpoint.attempt());
                statement.setString(5, checkpoint.type().name());
                statement.setString(6, checkpoint.status().name());
                statement.setString(7, checkpoint.reason());
                setString(statement, 8, checkpoint.name());
                statement.setTimestamp(9, Timestamp.from(checkpoint.triggeredAt()));
                statement.setString(10, checkpoint.directory());
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public void complete(
            String runId, long checkpointId, int attempt, Instant completedAt, long size, String tasksJson) {
        run("recording checkpoint " + checkpointId + " of run " + runId + " as completed", c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement checkpoint = c.prepareStatement(
                            "UPDATE checkpoint SET status = ?, completed_at = ?, size_bytes = ?, tasks_json = ?"
                                    + " WHERE run_id = ? AND checkpoint_id = ?");
                    PreparedStatement last = c.prepareStatement(
                            "UPDATE job_run_attempt SET last_checkpoint_id = ? WHERE run_id = ? AND attempt = ?")) {
                checkpoint.setString(1, Status.COMPLETED.name());
                checkpoint.setTimestamp(2, Timestamp.from(completedAt));
                checkpoint.setLong(3, size);
                checkpoint.setString(4, tasksJson);
                checkpoint.setString(5, runId);
                checkpoint.setLong(6, checkpointId);
                if (checkpoint.executeUpdate() != 1) {
                    throw new SQLException("the checkpoint is not recorded");
                }
                last.setLong(1, checkpointId);
                last.setString(2, runId);
                last.setInt(3, attempt);
                last.executeUpdate();
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    @Override
    public void fail(String runId, long checkpointId, Instant at, String errorCode, String errorMessage) {
        run("recording checkpoint " + checkpointId + " of run " + runId + " as failed", c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "UPDATE checkpoint SET status = ?, completed_at = ?, error_code = ?, error_message = ?"
                            + " WHERE run_id = ? AND checkpoint_id = ?")) {
                statement.setString(1, Status.FAILED.name());
                statement.setTimestamp(2, Timestamp.from(at));
                statement.setString(3, errorCode);
                statement.setString(4, errorMessage);
                statement.setString(5, runId);
                statement.setLong(6, checkpointId);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public void delete(String runId, long checkpointId) {
        run("deleting checkpoint " + checkpointId + " of run " + runId, c -> {
            try (PreparedStatement statement =
                    c.prepareStatement("DELETE FROM checkpoint WHERE run_id = ? AND checkpoint_id = ?")) {
                statement.setString(1, runId);
                statement.setLong(2, checkpointId);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public List<Checkpoint> list(String runId) {
        return run("reading the checkpoints of run " + runId, c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT run_id, checkpoint_id, job_name, attempt, type, status, trigger_reason, name,"
                            + " triggered_at, completed_at, size_bytes, path, error_code, error_message, tasks_json"
                            + " FROM checkpoint WHERE run_id = ? ORDER BY checkpoint_id DESC")) {
                statement.setString(1, runId);
                List<Checkpoint> checkpoints = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Timestamp completed = rows.getTimestamp(10);
                        long size = rows.getLong(11);
                        boolean sized = !rows.wasNull();
                        checkpoints.add(new Checkpoint(
                                rows.getString(1),
                                rows.getLong(2),
                                rows.getString(3),
                                rows.getInt(4),
                                CheckpointType.valueOf(rows.getString(5)),
                                Status.valueOf(rows.getString(6)),
                                rows.getString(7),
                                rows.getString(8),
                                rows.getTimestamp(9).toInstant(),
                                completed == null ? null : completed.toInstant(),
                                sized ? size : null,
                                rows.getString(12),
                                rows.getString(13),
                                rows.getString(14),
                                rows.getString(15)));
                    }
                }
                return checkpoints;
            }
        });
    }

    private static void setString(PreparedStatement statement, int index, @Nullable String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private PipeForgeException unavailable(String reason, Throwable cause) {
        return PipeForgeException.of(ErrorCode.METASTORE_UNAVAILABLE)
                .param("url", url)
                .param("reason", reason)
                .cause(cause)
                .build();
    }
}
