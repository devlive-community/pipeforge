/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.connector.jdbc.JdbcColumn;
import org.devlive.pipeforge.connector.jdbc.JdbcConnectionConfig;
import org.devlive.pipeforge.connector.jdbc.JdbcErrorKind;
import org.junit.jupiter.api.Test;

class PostgresDialectTest {

    private static final PostgresDialect DIALECT = new PostgresDialect();

    private static DataType type(String name, int sqlType, int size, int scale) {
        return DIALECT.toDataType(new JdbcColumn("c", sqlType, name, size, scale, true));
    }

    @Test
    void quotesWithDoubleQuotesAndTakesTheFirstPartAsSchema() {
        assertThat(DIALECT.name()).isEqualTo("postgres");
        assertThat(DIALECT.quote("order")).isEqualTo("\"order\"");
        assertThat(DIALECT.quote(TableId.parse("shop.orders"))).isEqualTo("\"shop\".\"orders\"");
        assertThat(DIALECT.catalogAndSchema(TableId.parse("shop.orders"))).containsExactly(null, "shop");
        assertThat(DIALECT.tableId("app", "shop", "orders")).isEqualTo(TableId.of("shop", "orders"));
        assertThat(DIALECT.systemSchemas()).containsExactlyInAnyOrder("INFORMATION_SCHEMA", "PG_CATALOG", "PG_TOAST");
        assertThat(DIALECT.insertStatement(TableId.parse("shop.orders"), List.of("id", "doc")))
                .isEqualTo("INSERT INTO \"shop\".\"orders\" (\"id\", \"doc\") VALUES (?, ?)");
    }

    @Test
    void mapsPostgresTypes() {
        assertThat(type("timestamptz", Types.TIMESTAMP, 35, 6)).isEqualTo(DataTypes.TIMESTAMP_LTZ(6));
        assertThat(type("timestamp", Types.TIMESTAMP, 29, 3)).isEqualTo(DataTypes.TIMESTAMP(3));
        assertThat(type("jsonb", Types.OTHER, 0, 0)).isEqualTo(DataTypes.JSON());
        assertThat(type("json", Types.OTHER, 0, 0)).isEqualTo(DataTypes.JSON());
        assertThat(type("numeric", Types.NUMERIC, 12, 2)).isEqualTo(DataTypes.DECIMAL(12, 2));
        assertThat(type("numeric", Types.NUMERIC, 0, 0)).as("no precision").isEqualTo(DataTypes.STRING());
        assertThat(type("numeric", Types.NUMERIC, 131089, 0)).as("unbounded").isEqualTo(DataTypes.STRING());
        assertThat(type("numeric", Types.NUMERIC, 50, 4)).as("wider than 38").isEqualTo(DataTypes.STRING());
        assertThat(type("uuid", Types.OTHER, 0, 0)).isEqualTo(DataTypes.STRING(36));
        assertThat(type("interval", Types.OTHER, 0, 0)).isEqualTo(DataTypes.STRING());
        assertThat(type("inet", Types.OTHER, 0, 0)).isEqualTo(DataTypes.STRING());
        assertThat(type("_int4", Types.ARRAY, 0, 0)).isEqualTo(DataTypes.ARRAY(DataTypes.INT32()));
        assertThat(type("_text", Types.ARRAY, 0, 0)).isEqualTo(DataTypes.ARRAY(DataTypes.STRING()));
        assertThat(type("bool", Types.BIT, 1, 0)).isEqualTo(DataTypes.BOOLEAN());
        assertThat(type("text", Types.VARCHAR, Integer.MAX_VALUE, 0)).isEqualTo(DataTypes.STRING());
        assertThat(type("bytea", Types.BINARY, Integer.MAX_VALUE, 0)).isEqualTo(DataTypes.BYTES());
        assertThat(DIALECT.toDataType(new JdbcColumn("c", Types.OTHER, "jsonb", 0, 0, false)))
                .isEqualTo(DataTypes.JSON().notNull());
        assertThatThrownBy(() -> type("_point", Types.ARRAY, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("_point");
        assertThatThrownBy(() -> type("point", Types.OTHER, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesByPostgresCodes() {
        assertThat(DIALECT.classify(new SQLException("no db", "3D000"))).isEqualTo(JdbcErrorKind.NOT_FOUND);
        assertThat(DIALECT.classify(new SQLException("no schema", "3F000"))).isEqualTo(JdbcErrorKind.NOT_FOUND);
        assertThat(DIALECT.classify(new SQLException("shutdown", "57P01"))).isEqualTo(JdbcErrorKind.CONNECTION);
        assertThat(DIALECT.classify(new SQLException("too many", "53300"))).isEqualTo(JdbcErrorKind.CONNECTION);
        assertThat(DIALECT.classify(new SQLException("lock", "55P03"))).isEqualTo(JdbcErrorKind.TRANSIENT);
        assertThat(DIALECT.classify(new SQLException("timeout", "57014"))).isEqualTo(JdbcErrorKind.TRANSIENT);
        assertThat(DIALECT.classify(new SQLException("deadlock", "40P01"))).isEqualTo(JdbcErrorKind.TRANSIENT);
        assertThat(DIALECT.classify(new SQLException("unique", "23505"))).isEqualTo(JdbcErrorKind.DATA);
        assertThat(DIALECT.classify(new SQLException("password", "28P01"))).isEqualTo(JdbcErrorKind.AUTH);
        assertThat(DIALECT.classify(new SQLException("syntax", "42601"))).isEqualTo(JdbcErrorKind.OTHER);
        assertThat(DIALECT.classify(new SQLException("none"))).isEqualTo(JdbcErrorKind.OTHER);
    }

    @Test
    void setsDriverDefaultsAndRefusesPropertiesThatLoadClasses() {
        JdbcConnectionConfig config = JdbcConnectionConfig.of(
                ReadonlyConfig.of(Map.of("url", "jdbc:postgresql://db/shop", "jdbc.properties.stringtype", "varchar")),
                DIALECT,
                "src");
        assertThat(config.properties())
                .containsEntry("reWriteBatchedInserts", "true")
                .as("the user wins")
                .containsEntry("stringtype", "varchar");
        for (String key : List.of(
                "socketFactory", "sslfactory", "sslhostnameverifier", "authenticationPluginClassName", "loggerFile")) {
            assertThatThrownBy(() -> JdbcConnectionConfig.of(
                            ReadonlyConfig.of(
                                    Map.of("url", "jdbc:postgresql://db/shop", "jdbc.properties." + key, "x")),
                            DIALECT,
                            "src"))
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                            .isEqualTo(ErrorCode.NATIVE_KEY_CONTROLLED));
        }
    }
}
