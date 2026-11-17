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
 * @param table the table to read, or {@code null} for a query or many tables
 * @param query the query to read, or {@code null} for tables
 * @param include with many tables: the patterns of the names to read; empty otherwise
 * @param exclude with many tables: the patterns of the names not to read
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
        java.util.List<String> include,
        java.util.List<String> exclude,
        TableId produced,
        @Nullable String splitColumn,
        JdbcSplitMode splitMode,
        int splitCount,
        int fetchSize,
        String node)
        implements Serializable {

    public JdbcSourceConfig {
        include = java.util.List.copyOf(include);
        exclude = java.util.List.copyOf(exclude);
    }

    /** Whether the source reads the tables {@code tables.include} selects. */
    public boolean manyTables() {
        return !include.isEmpty();
    }

    public static JdbcSourceConfig of(ReadonlyConfig config, JdbcDialect dialect, String node) {
        String table = config.get(JdbcOptions.TABLE);
        TableId id = table == null ? null : TableId.parse(table);
        return new JdbcSourceConfig(
                JdbcConnectionConfig.of(config, dialect, node),
                id,
                config.get(JdbcOptions.QUERY),
                config.getOptional(JdbcOptions.TABLES_INCLUDE).orElse(java.util.List.of()),
                config.get(JdbcOptions.TABLES_EXCLUDE),
                id != null ? id : TableId.of(node),
                config.get(JdbcOptions.SPLIT_COLUMN),
                config.get(JdbcOptions.SPLIT_MODE),
                config.get(JdbcOptions.SPLIT_COUNT),
                config.get(JdbcOptions.FETCH_SIZE),
                node);
    }
}
