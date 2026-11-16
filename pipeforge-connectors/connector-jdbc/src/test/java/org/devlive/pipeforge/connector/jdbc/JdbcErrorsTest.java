/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Map;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.BadRowException;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class JdbcErrorsTest {

    private static final JdbcConnectionConfig CONNECTION = JdbcConnectionConfig.of(
            ReadonlyConfig.of(Map.of("url", "jdbc:x://db:1/shop?password=p4ss", "username", "ann")),
            new GenericDialect(),
            "out");
    private static final TableId TABLE = TableId.parse("shop.orders");

    private static PipeForgeException error(SQLException e, boolean connecting) {
        return (PipeForgeException) JdbcErrors.of(e, new GenericDialect(), CONNECTION, connecting, TABLE, "out", false);
    }

    @Test
    void namesTheProblemWithItsCode() {
        PipeForgeException refused = error(new SQLException("no route", "08001"), true);
        assertThat(refused.code()).isEqualTo(ErrorCode.CONN_REFUSED);
        assertThat(refused.kind()).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(refused.getMessage())
                .isEqualTo("PF-CONN-0001 Cannot connect to jdbc:x://db:1/shop?password=***: SQLState 08001: no route");

        PipeForgeException unknown = error(new SQLException("unknown database", "3D000"), true);
        assertThat(unknown.code()).isEqualTo(ErrorCode.CONN_REFUSED);
        assertThat(unknown.kind()).as("retrying does not help").isEqualTo(ErrorKind.FATAL);
        assertThat(error(new SQLException("reset", "08S01"), false).code()).isEqualTo(ErrorCode.CONN_LOST);
        assertThat(error(new SQLTimeoutException("slow"), false).code()).isEqualTo(ErrorCode.CONN_TIMEOUT);
        PipeForgeException login = error(new SQLException("denied", "28000"), true);
        assertThat(login.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(login.params()).containsEntry("user", "ann");
        assertThat(error(new SQLException("no table", "42S02"), false).code()).isEqualTo(ErrorCode.TABLE_NOT_FOUND);
        PipeForgeException deadlock = error(new SQLException("deadlock", "40001"), false);
        assertThat(deadlock.code()).isEqualTo(ErrorCode.SINK_FAILED);
        assertThat(deadlock.kind()).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(error(new SQLException("syntax", "42000"), false).code()).isEqualTo(ErrorCode.SINK_FAILED);
        assertThat(JdbcErrors.of(
                        new SQLException("syntax", "42000"),
                        new GenericDialect(),
                        CONNECTION,
                        false,
                        null,
                        "src",
                        true))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SOURCE_FAILED));
    }

    @Test
    void dataErrorsAreBadRows() {
        assertThat(JdbcErrors.of(
                        new SQLException("out of range", "22003"),
                        new GenericDialect(),
                        CONNECTION,
                        false,
                        TABLE,
                        "src",
                        true))
                .isInstanceOf(BadRowException.class)
                .hasMessage("SQLState 22003: out of range");
        assertThat(JdbcErrors.reason(new SQLException("first line\nsecond line")))
                .isEqualTo("first line");
    }
}
