/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.devlive.pipeforge.api.row.BadRowException;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/** Turns database errors into the errors users see, after what the dialect says they mean. */
public final class JdbcErrors {

    private JdbcErrors() {}

    /** The SQLState and the first line of the message. */
    public static String reason(SQLException error) {
        String message = String.valueOf(error.getMessage());
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return error.getSQLState() == null ? line : "SQLState " + error.getSQLState() + ": " + line;
    }

    /**
     * The error for {@code error}.
     *
     * @param connecting whether it happened while connecting: then every error but a login or a
     *     timeout means the connection could not be made, worth retrying only for connection errors
     * @param table the table the statement was about, or {@code null}
     * @param source whether a source or a sink met it, for errors without a code of their own
     */
    public static RuntimeException of(
            SQLException error,
            JdbcDialect dialect,
            JdbcConnectionConfig connection,
            boolean connecting,
            @Nullable TableId table,
            String node,
            boolean source) {
        String target = connection.target();
        String reason = reason(error);
        JdbcErrorKind kind = dialect.classify(error);
        if (kind == JdbcErrorKind.DATA) {
            // a value of the current row: a bad row for the dirty data policy
            return new BadRowException(reason, error);
        }
        if (connecting
                && (kind == JdbcErrorKind.OTHER
                        || kind == JdbcErrorKind.NOT_FOUND
                        || (kind == JdbcErrorKind.TRANSIENT && !(error instanceof SQLTimeoutException)))) {
            return PipeForgeException.of(ErrorCode.CONN_REFUSED)
                    .param("target", target)
                    .param("reason", reason)
                    .kind(kind == JdbcErrorKind.TRANSIENT ? ErrorKind.TRANSIENT : ErrorKind.FATAL)
                    .cause(error)
                    .build();
        }
        PipeForgeException.Builder builder =
                switch (kind) {
                    case CONNECTION -> PipeForgeException.of(connecting ? ErrorCode.CONN_REFUSED : ErrorCode.CONN_LOST)
                            .param("target", target)
                            .param("reason", reason);
                    case TRANSIENT -> error instanceof SQLTimeoutException
                            ? PipeForgeException.of(ErrorCode.CONN_TIMEOUT)
                                    .param("target", target)
                                    .param("reason", reason)
                            : failed(node, reason, source).kind(ErrorKind.TRANSIENT);
                    case AUTH -> PipeForgeException.of(ErrorCode.AUTH_FAILED)
                            .param("target", target)
                            .param("user", String.valueOf(connection.username()))
                            .param("reason", reason);
                    case NOT_FOUND -> table == null
                            ? failed(node, reason, source)
                            : PipeForgeException.of(ErrorCode.TABLE_NOT_FOUND)
                                    .param("table", table.toQualifiedName())
                                    .param("target", target);
                    case DATA, OTHER -> failed(node, reason, source);
                };
        return builder.cause(error).build();
    }

    private static PipeForgeException.Builder failed(String node, String reason, boolean source) {
        return PipeForgeException.of(source ? ErrorCode.SOURCE_FAILED : ErrorCode.SINK_FAILED)
                .param("node", node)
                .param("reason", reason);
    }
}
