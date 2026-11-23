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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * The progress table of a JDBC sink (design 06 section 5.4): one row per run, node and split with
 * the position of the source up to which the rows were committed. The writer updates it in the
 * transaction of the rows, so that the rows and their position commit together.
 *
 * <pre>{@code
 * <name>(job_id VARCHAR(128), node_id VARCHAR(128), split_id VARCHAR(384), watermark VARCHAR(512),
 *        updated_at TIMESTAMP, PRIMARY KEY (job_id, node_id, split_id))
 * }</pre>
 */
final class JdbcProgressTable {

    private final String runId;
    private final String nodeId;
    private final String update;
    private final String insert;
    private final String select;
    private final String delete;

    private JdbcProgressTable(JdbcDialect dialect, TableId id, String runId, String nodeId) {
        this.runId = runId;
        this.nodeId = nodeId;
        String table = dialect.quote(id);
        String key = dialect.quote("job_id") + " = ? AND " + dialect.quote("node_id") + " = ? AND "
                + dialect.quote("split_id") + " = ?";
        this.update = "UPDATE " + table + " SET " + dialect.quote("watermark") + " = ?, " + dialect.quote("updated_at")
                + " = ? WHERE " + key;
        this.insert = "INSERT INTO " + table + " (" + dialect.quote("job_id") + ", " + dialect.quote("node_id")
                + ", " + dialect.quote("split_id") + ", " + dialect.quote("watermark") + ", "
                + dialect.quote("updated_at") + ") VALUES (?, ?, ?, ?, ?)";
        this.select = "SELECT " + dialect.quote("split_id") + ", " + dialect.quote("watermark") + " FROM " + table
                + " WHERE " + dialect.quote("job_id") + " = ? AND " + dialect.quote("node_id") + " = ?";
        this.delete = "DELETE FROM " + table + " WHERE " + key;
    }

    /**
     * The progress table {@code name} next to {@code target}, created when it is missing.
     *
     * @throws SQLException when it is missing and cannot be created
     */
    static JdbcProgressTable open(
            Connection connection, JdbcDialect dialect, TableId target, String name, String runId, String nodeId)
            throws SQLException {
        TableId id = new TableId(target.catalog(), target.database(), target.schema(), name);
        boolean exists;
        try {
            JdbcSchemas.describe(connection, dialect, id, name);
            exists = true;
        } catch (PipeForgeException e) {
            exists = false;
        }
        if (!exists) {
            String create = "CREATE TABLE " + dialect.quote(id) + " ("
                    + dialect.quote("job_id") + " VARCHAR(128) NOT NULL, "
                    + dialect.quote("node_id") + " VARCHAR(128) NOT NULL, "
                    + dialect.quote("split_id") + " VARCHAR(384) NOT NULL, "
                    + dialect.quote("watermark") + " VARCHAR(512) NOT NULL, "
                    + dialect.quote("updated_at") + " TIMESTAMP NOT NULL, "
                    + "PRIMARY KEY (" + dialect.quote("job_id") + ", " + dialect.quote("node_id") + ", "
                    + dialect.quote("split_id") + "))";
            try (var statement = connection.createStatement()) {
                statement.execute(create);
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
        return new JdbcProgressTable(dialect, id, runId, nodeId);
    }

    /** Records the positions, in the connection's transaction; the caller commits. */
    void record(Connection connection, Map<String, String> positions) throws SQLException {
        if (positions.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement updating = connection.prepareStatement(update);
                PreparedStatement inserting = connection.prepareStatement(insert)) {
            for (Map.Entry<String, String> position : positions.entrySet()) {
                updating.setString(1, position.getValue());
                updating.setTimestamp(2, now);
                updating.setString(3, runId);
                updating.setString(4, nodeId);
                updating.setString(5, position.getKey());
                if (updating.executeUpdate() == 0) {
                    inserting.setString(1, runId);
                    inserting.setString(2, nodeId);
                    inserting.setString(3, position.getKey());
                    inserting.setString(4, position.getValue());
                    inserting.setTimestamp(5, now);
                    inserting.executeUpdate();
                }
            }
        }
    }

    /** The positions recorded for {@code splitIds}. */
    Map<String, String> recorded(Connection connection, Collection<String> splitIds) throws SQLException {
        Map<String, String> found = new HashMap<>();
        try (PreparedStatement selecting = connection.prepareStatement(select)) {
            selecting.setString(1, runId);
            selecting.setString(2, nodeId);
            try (ResultSet rows = selecting.executeQuery()) {
                while (rows.next()) {
                    if (splitIds.contains(rows.getString(1))) {
                        found.put(rows.getString(1), rows.getString(2));
                    }
                }
            }
        }
        return found;
    }

    /** Removes the positions of {@code splitIds}, in the connection's transaction. */
    void forget(Connection connection, Collection<String> splitIds) throws SQLException {
        try (PreparedStatement deleting = connection.prepareStatement(delete)) {
            for (String split : splitIds) {
                deleting.setString(1, runId);
                deleting.setString(2, nodeId);
                deleting.setString(3, split);
                deleting.addBatch();
            }
            deleting.executeBatch();
        }
    }
}
