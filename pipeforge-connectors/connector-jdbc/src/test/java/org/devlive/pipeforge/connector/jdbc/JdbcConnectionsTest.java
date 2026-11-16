/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.Map;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class JdbcConnectionsTest {

    private static final ClassLoader LOADER = JdbcConnectionsTest.class.getClassLoader();

    private static JdbcConnectionConfig config(Map<String, Object> options) {
        return JdbcConnectionConfig.of(ReadonlyConfig.of(options), H2.DIALECT, "src");
    }

    private static PipeForgeException failure(Map<String, Object> options) {
        try (Connection ignored = JdbcConnections.open(config(options), H2.DIALECT, LOADER, "src")) {
            throw new AssertionError("connected");
        } catch (PipeForgeException e) {
            return e;
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void connectsWithTheDriverOfTheUrlOrTheConfiguredClass() throws Exception {
        try (Connection connection = JdbcConnections.open(
                config(Map.of("url", "jdbc:h2:mem:connections", "username", "sa")), H2.DIALECT, LOADER, "src")) {
            assertThat(connection.isValid(1)).isTrue();
        }
        try (Connection connection = JdbcConnections.open(
                config(Map.of("url", "jdbc:h2:mem:connections", "driver", "org.h2.Driver")),
                H2.DIALECT,
                LOADER,
                "src")) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    @Test
    void reportsMissingDriversRefusedConnectionsAndLogins() throws Exception {
        PipeForgeException noClass = failure(Map.of("url", "jdbc:h2:mem:x", "driver", "com.example.NoDriver"));
        assertThat(noClass.code()).isEqualTo(ErrorCode.DRIVER_MISSING);
        assertThat(noClass.params()).containsEntry("driver", "com.example.NoDriver");

        PipeForgeException noDriver = failure(Map.of("url", "jdbc:nosuchdb://h/x"));
        assertThat(noDriver.code()).isEqualTo(ErrorCode.DRIVER_MISSING);
        assertThat(noDriver.params()).containsEntry("driver", "a JDBC driver for jdbc:nosuchdb");

        PipeForgeException refused = failure(Map.of("url", "jdbc:h2:tcp://127.0.0.1:1/x;password=p4ss"));
        assertThat(refused.code()).as("H2 says so with a state of its own").isEqualTo(ErrorCode.CONN_REFUSED);
        assertThat(refused.getMessage())
                .contains("jdbc:h2:tcp://127.0.0.1:1/x;password=***")
                .doesNotContain("p4ss");

        H2 h2 = new H2("logins");
        h2.execute("CREATE USER IF NOT EXISTS ann PASSWORD 'right'");
        PipeForgeException login = failure(Map.of("url", h2.url, "username", "ann", "password", "wrong"));
        assertThat(login.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(login.params()).containsEntry("user", "ann");
        assertThat(login.getMessage()).doesNotContain("wrong");
    }

    @Test
    void schemaErrorsNameTheTable() {
        assertThatThrownBy(() -> {
                    try (Connection connection = JdbcConnections.open(
                            config(Map.of("url", "jdbc:h2:mem:schemas")), H2.DIALECT, LOADER, "src")) {
                        JdbcSchemas.describe(
                                connection,
                                H2.DIALECT,
                                org.devlive.pipeforge.api.row.TableId.parse("shop.nothing"),
                                "jdbc:h2:mem:schemas");
                    }
                })
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.TABLE_NOT_FOUND);
                    assertThat(e.getMessage())
                            .isEqualTo("PF-META-0001 Table shop.nothing does not exist in jdbc:h2:mem:schemas");
                });
    }
}
