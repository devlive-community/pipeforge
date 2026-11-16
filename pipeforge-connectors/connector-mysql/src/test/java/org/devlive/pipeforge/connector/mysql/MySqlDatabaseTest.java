/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.connector.jdbc.JdbcConnectionConfig;
import org.devlive.pipeforge.connector.jdbc.JdbcConnections;
import org.devlive.pipeforge.connector.jdbc.JdbcErrorKind;
import org.devlive.pipeforge.connector.jdbc.JdbcSchemas;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The dialect against real MySQL servers: every verified version. */
class MySqlDatabaseTest {

    private static final MySqlDialect DIALECT = new MySqlDialect();
    private static final Map<String, MySQLContainer> SERVERS = new ConcurrentHashMap<>();
    private static final TableId TYPES = TableId.parse("shop.types");

    @BeforeAll
    static void docker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @AfterAll
    static void stop() {
        SERVERS.values().forEach(MySQLContainer::stop);
        SERVERS.clear();
    }

    /** The server of {@code version}, started once for all tests. */
    @SuppressWarnings("resource") // stopped after all tests
    static MySQLContainer server(String version) {
        return SERVERS.computeIfAbsent(version, v -> {
            MySQLContainer server = new MySQLContainer(DockerImageName.parse("mysql:" + v))
                    .withDatabaseName("shop")
                    .withUsername("ann")
                    .withPassword("s3cret");
            server.start();
            return server;
        });
    }

    static JdbcConnectionConfig config(MySQLContainer server, String password) {
        return JdbcConnectionConfig.of(
                ReadonlyConfig.of(Map.of("url", server.getJdbcUrl(), "username", "ann", "password", password)),
                DIALECT,
                "src");
    }

    static Connection connect(MySQLContainer server) {
        return JdbcConnections.open(config(server, "s3cret"), DIALECT, MySqlDatabaseTest.class.getClassLoader(), "src");
    }

    static void execute(Connection connection, String... statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** Every column type of the table {@code types} and the logical type it maps to. */
    static final Map<String, Object[]> COLUMNS = new LinkedHashMap<>();

    static {
        column("id", "BIGINT NOT NULL", DataTypes.INT64().notNull(), 1L);
        column("b", "TINYINT(1)", DataTypes.BOOLEAN(), true);
        column("t", "TINYINT", DataTypes.INT8(), (byte) -128);
        column("tu", "TINYINT UNSIGNED", DataTypes.INT16(), (short) 255);
        column("s", "SMALLINT", DataTypes.INT16(), (short) -32768);
        column("su", "SMALLINT UNSIGNED", DataTypes.INT32(), 65535);
        column("m", "MEDIUMINT", DataTypes.INT32(), -8388608);
        column("mu", "MEDIUMINT UNSIGNED", DataTypes.INT32(), 16777215);
        column("i", "INT", DataTypes.INT32(), Integer.MIN_VALUE);
        column("iu", "INT UNSIGNED", DataTypes.INT64(), 4294967295L);
        column("big", "BIGINT", DataTypes.INT64(), Long.MIN_VALUE);
        column("bigu", "BIGINT UNSIGNED", DataTypes.DECIMAL(20, 0), new BigDecimal("18446744073709551615"));
        column("f", "FLOAT", DataTypes.FLOAT32(), 1.5f);
        column("d", "DOUBLE", DataTypes.FLOAT64(), -2.25);
        column("de", "DECIMAL(12,2)", DataTypes.DECIMAL(12, 2), new BigDecimal("1234567890.12"));
        column("c", "CHAR(4)", DataTypes.STRING(4), "abcd");
        column("v", "VARCHAR(20)", DataTypes.STRING(20), "héllo 世界");
        column("tx", "TEXT", DataTypes.STRING(), "four bytes 😀 and more");
        column("e", "ENUM('a','b')", DataTypes.STRING(1), "b");
        column("bi", "BINARY(3)", DataTypes.BYTES(3), new byte[] {1, 2, 3});
        column("vb", "VARBINARY(8)", DataTypes.BYTES(8), new byte[] {0, -1, 127});
        column("bl", "BLOB", DataTypes.BYTES(), new byte[1000]);
        column("bits", "BIT(8)", DataTypes.BYTES(), new byte[] {(byte) 0xA5});
        column("dt", "DATE", DataTypes.DATE(), LocalDate.of(9999, 12, 31));
        column("tm", "TIME(3)", DataTypes.TIME(3), LocalTime.of(23, 59, 59, 999_000_000));
        column("dtm", "DATETIME(6)", DataTypes.TIMESTAMP(6), LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000));
        column("ts", "TIMESTAMP(3) NULL", DataTypes.TIMESTAMP_LTZ(3), Instant.parse("2038-01-19T03:14:07.999Z"));
        column("y", "YEAR", DataTypes.INT16(), (short) 2155);
        column("j", "JSON", DataTypes.JSON(), "{\"a\": [1, 2.5, null], \"b\": \"文字\"}");
    }

    private static void column(String name, String sql, DataType type, Object value) {
        COLUMNS.put(name, new Object[] {sql, type, value});
    }

    static void createTypes(Connection connection) throws SQLException {
        List<String> columns = new ArrayList<>();
        COLUMNS.forEach((name, spec) -> columns.add(DIALECT.quote(name) + " " + spec[0]));
        execute(
                connection,
                "DROP TABLE IF EXISTS `shop`.`types`",
                "CREATE TABLE `shop`.`types` (" + String.join(", ", columns) + ", PRIMARY KEY (`id`))");
    }

    @ParameterizedTest(name = "MySQL {0}")
    @ValueSource(strings = {"8.0", "8.4"})
    void describesEveryTypeTheWayTheDialectMapsIt(String version) throws Exception {
        try (Connection connection = connect(server(version))) {
            createTypes(connection);
            CatalogTable table =
                    JdbcSchemas.describe(connection, DIALECT, TYPES, "mysql").table();
            assertThat(table.primaryKey()).containsExactly("id");
            assertThat(table.columnNames()).containsExactlyElementsOf(COLUMNS.keySet());
            for (Column column : table.columns()) {
                assertThat(column.type()).as(column.name()).isEqualTo(COLUMNS.get(column.name())[1]);
            }
        }
    }

    @ParameterizedTest(name = "MySQL {0}")
    @ValueSource(strings = {"8.0", "8.4"})
    void writesAndReadsBackTheBoundaryValuesOfEveryType(String version) throws Exception {
        try (Connection connection = connect(server(version))) {
            createTypes(connection);
            List<String> names = new ArrayList<>(COLUMNS.keySet());
            List<DataType> types =
                    names.stream().map(n -> (DataType) COLUMNS.get(n)[1]).toList();
            try (PreparedStatement insert = connection.prepareStatement(DIALECT.insertStatement(TYPES, names))) {
                for (int i = 0; i < names.size(); i++) {
                    DIALECT.write(insert, i + 1, COLUMNS.get(names.get(i))[2], types.get(i));
                }
                insert.executeUpdate();
                // and a row of NULLs
                DIALECT.write(insert, 1, 2L, types.get(0));
                for (int i = 1; i < names.size(); i++) {
                    DIALECT.write(insert, i + 1, null, types.get(i));
                }
                insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(DIALECT.selectQuery(TYPES, names, null) + " ORDER BY `id`")) {
                assertThat(rs.next()).isTrue();
                for (int i = 0; i < names.size(); i++) {
                    Object expected = COLUMNS.get(names.get(i))[2];
                    assertThat(DIALECT.read(rs, i + 1, types.get(i)))
                            .as(names.get(i))
                            .isEqualTo(expected);
                }
                assertThat(rs.next()).isTrue();
                for (int i = 1; i < names.size(); i++) {
                    assertThat(DIALECT.read(rs, i + 1, types.get(i)))
                            .as(names.get(i))
                            .isNull();
                }
            }
        }
        // the instant was stored as such: a session in Tokyo sees it in its own time
        try (Connection tokyo = connect(server(version))) {
            execute(tokyo, "SET time_zone = '+09:00'");
            try (Statement statement = tokyo.createStatement();
                    ResultSet rs =
                            statement.executeQuery("SELECT CAST(`ts` AS CHAR) FROM `shop`.`types` WHERE `id` = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("2038-01-19 12:14:07.999");
            }
        }
    }

    @ParameterizedTest(name = "MySQL {0}")
    @ValueSource(strings = {"8.0", "8.4"})
    void streamsLargeResultsRowByRow(String version) throws Exception {
        try (Connection connection = connect(server(version))) {
            execute(
                    connection,
                    "DROP TABLE IF EXISTS `shop`.`many`",
                    "CREATE TABLE `shop`.`many` (`id` INT PRIMARY KEY)",
                    "SET SESSION cte_max_recursion_depth = 30000",
                    "INSERT INTO `shop`.`many` WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n"
                            + " WHERE i < 20000) SELECT i FROM n");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT `id` FROM `shop`.`many`", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                DIALECT.prepareStreaming(connection, statement, 1000);
                assertThat(statement.getFetchSize()).isEqualTo(Integer.MIN_VALUE);
                int rows = 0;
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        rows++;
                    }
                }
                assertThat(rows).isEqualTo(20_000);
            }
        }
    }

    @ParameterizedTest(name = "MySQL {0}")
    @ValueSource(strings = {"8.0", "8.4"})
    void classifiesTheErrorsTheServerReports(String version) throws Exception {
        MySQLContainer server = server(version);
        try (Connection connection = connect(server)) {
            execute(
                    connection,
                    "DROP TABLE IF EXISTS `shop`.`keys`",
                    "CREATE TABLE `shop`.`keys` (`id` INT PRIMARY KEY, `name` VARCHAR(4) NOT NULL)",
                    "INSERT INTO `shop`.`keys` VALUES (1, 'a')");
            assertThat(kind(connection, "INSERT INTO `shop`.`keys` VALUES (1, 'b')"))
                    .as("duplicate")
                    .isEqualTo(JdbcErrorKind.DATA);
            assertThat(kind(connection, "INSERT INTO `shop`.`keys` VALUES (2, 'too long')"))
                    .as("too long")
                    .isEqualTo(JdbcErrorKind.DATA);
            assertThat(kind(connection, "INSERT INTO `shop`.`keys` VALUES (3, NULL)"))
                    .as("NULL")
                    .isEqualTo(JdbcErrorKind.DATA);
            assertThat(kind(connection, "INSERT INTO `shop`.`keys` VALUES ('x', 'a')"))
                    .as("incorrect value")
                    .isEqualTo(JdbcErrorKind.DATA);
            assertThat(kind(connection, "SELECT * FROM `shop`.`nothing`")).isEqualTo(JdbcErrorKind.NOT_FOUND);
            assertThat(kind(connection, "SELEKT 1")).isEqualTo(JdbcErrorKind.OTHER);

            // a row another transaction holds: the lock wait times out
            try (Connection holder = connect(server)) {
                holder.setAutoCommit(false);
                execute(holder, "UPDATE `shop`.`keys` SET `name` = 'h' WHERE `id` = 1");
                execute(connection, "SET SESSION innodb_lock_wait_timeout = 1");
                assertThat(kind(connection, "UPDATE `shop`.`keys` SET `name` = 'w' WHERE `id` = 1"))
                        .as("lock wait timeout")
                        .isEqualTo(JdbcErrorKind.TRANSIENT);
                holder.rollback();
            }
        }
        assertThatThrownBy(() -> JdbcConnections.open(
                        config(server, "wrong"), DIALECT, MySqlDatabaseTest.class.getClassLoader(), "src"))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.AUTH_FAILED);
                    assertThat(e.params()).containsEntry("user", "ann");
                });
    }

    private static JdbcErrorKind kind(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            throw new AssertionError("no error for " + sql);
        } catch (SQLException e) {
            return DIALECT.classify(e);
        }
    }
}
