/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.BatchUpdateException;
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

class MySqlDialectTest {

    private static final MySqlDialect DIALECT = new MySqlDialect();

    private static DataType type(String name, int sqlType, int size, int scale) {
        return DIALECT.toDataType(new JdbcColumn("c", sqlType, name, size, scale, true));
    }

    @Test
    void quotesWithBackticksAndTakesTheDatabaseAsCatalog() {
        assertThat(DIALECT.quote("order")).isEqualTo("`order`");
        assertThat(DIALECT.quote("a`b")).isEqualTo("`a``b`");
        assertThat(DIALECT.quote(TableId.parse("shop.orders"))).isEqualTo("`shop`.`orders`");
        assertThat(DIALECT.quote(TableId.of("orders"))).isEqualTo("`orders`");
        assertThat(DIALECT.catalogAndSchema(TableId.parse("shop.orders"))).containsExactly("shop", null);
        assertThat(DIALECT.insertStatement(TableId.parse("shop.orders"), List.of("id", "name")))
                .isEqualTo("INSERT INTO `shop`.`orders` (`id`, `name`) VALUES (?, ?)");
        assertThat(DIALECT.minMaxQuery(TableId.parse("shop.orders"), "id"))
                .isEqualTo("SELECT MIN(`id`), MAX(`id`) FROM `shop`.`orders`");
    }

    @Test
    void widensUnsignedIntegersAndMapsMySqlTypes() {
        assertThat(type("TINYINT UNSIGNED", Types.TINYINT, 3, 0)).isEqualTo(DataTypes.INT16());
        assertThat(type("SMALLINT UNSIGNED", Types.SMALLINT, 5, 0)).isEqualTo(DataTypes.INT32());
        assertThat(type("MEDIUMINT", Types.INTEGER, 7, 0)).isEqualTo(DataTypes.INT32());
        assertThat(type("MEDIUMINT UNSIGNED", Types.INTEGER, 8, 0)).isEqualTo(DataTypes.INT32());
        assertThat(type("INT UNSIGNED", Types.INTEGER, 10, 0)).isEqualTo(DataTypes.INT64());
        assertThat(type("BIGINT UNSIGNED", Types.BIGINT, 20, 0)).isEqualTo(DataTypes.DECIMAL(20, 0));
        assertThat(type("YEAR", Types.DATE, 4, 0)).isEqualTo(DataTypes.INT16());
        assertThat(type("BIT", Types.BIT, 1, 0)).as("one bit").isEqualTo(DataTypes.BOOLEAN());
        assertThat(type("BIT", Types.BIT, 8, 0)).isEqualTo(DataTypes.BYTES());
        assertThat(type("JSON", Types.LONGVARCHAR, 1_073_741_824, 0)).isEqualTo(DataTypes.JSON());
        assertThat(type("TIMESTAMP", Types.TIMESTAMP, 23, 3)).isEqualTo(DataTypes.TIMESTAMP_LTZ(3));
        assertThat(type("DATETIME", Types.TIMESTAMP, 26, 6)).isEqualTo(DataTypes.TIMESTAMP(6));
        assertThat(type("TIME", Types.TIME, 12, 3)).isEqualTo(DataTypes.TIME(3));
        assertThat(type("TIME", Types.TIME, 15, 0)).as("from the display size").isEqualTo(DataTypes.TIME(6));
        assertThat(type("TIME", Types.TIME, 8, 0)).isEqualTo(DataTypes.TIME(0));
        assertThat(type("DATETIME", Types.TIMESTAMP, 26, 0)).isEqualTo(DataTypes.TIMESTAMP(6));
        assertThat(type("TIMESTAMP", Types.TIMESTAMP, 19, 0)).isEqualTo(DataTypes.TIMESTAMP_LTZ(0));
        assertThat(type("INT", Types.INTEGER, 10, 0)).as("the rest is ANSI").isEqualTo(DataTypes.INT32());
        assertThat(DIALECT.toDataType(new JdbcColumn("c", Types.INTEGER, "INT UNSIGNED", 10, 0, false)))
                .isEqualTo(DataTypes.INT64().notNull());
        assertThatThrownBy(() -> type("GEOMETRY", Types.BINARY + 1000, 0, 0))
                .hasMessageContaining("GEOMETRY, which the mysql dialect cannot read");
    }

    @Test
    void classifiesByMySqlErrorCodes() {
        assertThat(DIALECT.classify(new SQLException("lock wait", "HY000", 1205)))
                .isEqualTo(JdbcErrorKind.TRANSIENT);
        assertThat(DIALECT.classify(new SQLException("deadlock", "40001", 1213)))
                .isEqualTo(JdbcErrorKind.TRANSIENT);
        assertThat(DIALECT.classify(new SQLException("gone away", "HY000", 2006)))
                .isEqualTo(JdbcErrorKind.CONNECTION);
        assertThat(DIALECT.classify(new SQLException("denied", "28000", 1045))).isEqualTo(JdbcErrorKind.AUTH);
        assertThat(DIALECT.classify(new SQLException("no table", "42S02", 1146)))
                .isEqualTo(JdbcErrorKind.NOT_FOUND);
        assertThat(DIALECT.classify(new SQLException("incorrect value", "HY000", 1366)))
                .isEqualTo(JdbcErrorKind.DATA);
        assertThat(DIALECT.classify(new SQLException("duplicate", "23000", 1062)))
                .isEqualTo(JdbcErrorKind.DATA);
        assertThat(DIALECT.classify(new SQLException("syntax", "42000", 1064))).isEqualTo(JdbcErrorKind.OTHER);
        assertThat(DIALECT.classify(new SQLException("comms", "08S01", 0)))
                .as("by SQLState")
                .isEqualTo(JdbcErrorKind.CONNECTION);
        BatchUpdateException batch = new BatchUpdateException("batch", "HY000", 0, new int[0]);
        batch.setNextException(new SQLException("too long", "22001", 1406));
        assertThat(DIALECT.classify(batch)).isEqualTo(JdbcErrorKind.DATA);
    }

    @Test
    void setsDriverDefaultsAndRefusesUnsafeProperties() {
        JdbcConnectionConfig config = JdbcConnectionConfig.of(
                ReadonlyConfig.of(Map.of("url", "jdbc:mysql://db/shop", "jdbc.properties.characterEncoding", "GBK")),
                DIALECT,
                "src");
        assertThat(config.properties())
                .containsEntry("rewriteBatchedStatements", "true")
                .containsEntry("yearIsDateType", "false")
                .containsEntry("connectionTimeZone", "UTC")
                .as("the user wins")
                .containsEntry("characterEncoding", "GBK");
        for (String key : List.of("allowLoadLocalInfile", "allowUrlInLocalInfile", "autoReconnect")) {
            assertThatThrownBy(() -> JdbcConnectionConfig.of(
                            ReadonlyConfig.of(Map.of("url", "jdbc:mysql://db/shop", "jdbc.properties." + key, "true")),
                            DIALECT,
                            "src"))
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                            .isEqualTo(ErrorCode.NATIVE_KEY_CONTROLLED));
        }
    }
}
