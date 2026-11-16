/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.io.Serializable;
import java.time.Duration;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.TableId;

/**
 * The settings of a JDBC sink node.
 *
 * @param connection how to connect
 * @param table the table to write into
 * @param batchSize rows per batch and transaction
 * @param maxRetries how often a batch that failed for a transient reason is tried again
 * @param retryBackoff the wait before the first retry; it doubles with every retry
 * @param node the node name, for errors
 */
public record JdbcSinkConfig(
        JdbcConnectionConfig connection,
        TableId table,
        int batchSize,
        int maxRetries,
        Duration retryBackoff,
        String node)
        implements Serializable {

    public static JdbcSinkConfig of(ReadonlyConfig config, JdbcDialect dialect, String node) {
        return new JdbcSinkConfig(
                JdbcConnectionConfig.of(config, dialect, node),
                TableId.parse(config.get(JdbcOptions.TABLE)),
                config.get(JdbcOptions.BATCH_SIZE),
                config.get(JdbcOptions.MAX_RETRIES),
                config.get(JdbcOptions.RETRY_BACKOFF),
                node);
    }
}
