/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class JdbcConnectionConfigTest {

    /** A dialect with a default and a controlled property, as a database plugin has. */
    static final class Strict extends AbstractJdbcDialect {
        @Override
        public String name() {
            return "strict";
        }

        @Override
        public Map<String, String> defaultProperties() {
            return Map.of("rewriteBatchedStatements", "true", "connectTimeout", "5000");
        }

        @Override
        public Map<String, String> controlledProperties() {
            return Map.of("autocommit", "each batch is a transaction the writer controls");
        }
    }

    @Test
    void mergesDefaultsStandardOptionsAndNativeProperties() {
        JdbcConnectionConfig config = JdbcConnectionConfig.of(
                ReadonlyConfig.of(Map.of(
                        "url", "jdbc:x://h/db",
                        "username", "ann",
                        "password", "s3cret",
                        "jdbc.properties.connectTimeout", "9000",
                        "jdbc.properties", Map.of("ssl", "true"))),
                new Strict(),
                "out");
        assertThat(config.properties())
                .containsEntry("rewriteBatchedStatements", "true")
                .containsEntry("connectTimeout", "9000")
                .containsEntry("ssl", "true")
                .containsEntry("user", "ann")
                .containsEntry("password", "s3cret");
        assertThat(config.username()).isEqualTo("ann");
        assertThat(config.driverProperties()).containsEntry("password", "s3cret");
        assertThat(config.toString()).doesNotContain("s3cret").contains("password=***");
    }

    @Test
    void refusesControlledProperties() {
        assertThatThrownBy(() -> JdbcConnectionConfig.of(
                        ReadonlyConfig.of(Map.of("url", "jdbc:x://h/db", "jdbc.properties.autocommit", "true")),
                        new Strict(),
                        "out"))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NATIVE_KEY_CONTROLLED);
                    assertThat(e.params())
                            .containsEntry("key", "jdbc.properties.autocommit")
                            .containsEntry("node", "out");
                    assertThat(e.scope().node()).isEqualTo("out");
                });
    }

    @Test
    void redactsCredentialsInUrls() {
        assertThat(JdbcConnectionConfig.redact("jdbc:mysql://ann:s3cret@db:3306/shop?useSSL=true&password=p4ss"))
                .isEqualTo("jdbc:mysql://***@db:3306/shop?useSSL=true&password=***");
        assertThat(JdbcConnectionConfig.redact("jdbc:sqlserver://h;user=ann;pwd=x;encrypt=true"))
                .isEqualTo("jdbc:sqlserver://h;user=ann;pwd=***;encrypt=true");
        assertThat(JdbcConnectionConfig.redact("jdbc:h2:mem:x")).isEqualTo("jdbc:h2:mem:x");
    }
}
