/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.io.Serializable;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.TableId;
import org.jspecify.annotations.Nullable;

/**
 * The settings of a JDBC source node.
 *
 * @param connection how to connect
 * @param table the table to read, or {@code null} for a query
 * @param query the query to read, or {@code null} for a table
 * @param produced the name of the table the source produces: the table, or the node for a query
 * @param splitColumn the configured split column, or {@code null} for the primary key
 * @param splitMode how to split
 * @param splitCount how many ranges; 0 for four per reader
 * @param fetchSize rows per round trip
 * @param node the node name, for errors
 */
public record JdbcSourceConfig(
        JdbcConnectionConfig connection,
        @Nullable TableId table,
        @Nullable String query,
        TableId produced,
        @Nullable String splitColumn,
        JdbcSplitMode splitMode,
        int splitCount,
        int fetchSize,
        String node)
        implements Serializable {

    public static JdbcSourceConfig of(ReadonlyConfig config, JdbcDialect dialect, String node) {
        String table = config.get(JdbcOptions.TABLE);
        TableId id = table == null ? null : TableId.parse(table);
        return new JdbcSourceConfig(
                JdbcConnectionConfig.of(config, dialect, node),
                id,
                config.get(JdbcOptions.QUERY),
                id != null ? id : TableId.of(node),
                config.get(JdbcOptions.SPLIT_COLUMN),
                config.get(JdbcOptions.SPLIT_MODE),
                config.get(JdbcOptions.SPLIT_COUNT),
                config.get(JdbcOptions.FETCH_SIZE),
                node);
    }
}
