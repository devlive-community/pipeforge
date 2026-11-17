/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

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
import java.util.Arrays;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The dialect against real PostgreSQL servers: every verified version. */
class PostgresDatabaseTest {

    private static final PostgresDialect DIALECT = new PostgresDialect();
    private static final Map<String, PostgreSQLContainer> SERVERS = new ConcurrentHashMap<>();
    private static final TableId TYPES = TableId.parse("shop.types");

    @BeforeAll
    static void docker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @AfterAll
    static void stop() {
        SERVERS.values().forEach(PostgreSQLContainer::stop);
        SERVERS.clear();
    }

    /** The server of {@code version}, started once for all tests. */
    @SuppressWarnings("resource") // stopped after all tests
    static PostgreSQLContainer server(String version) {
        return SERVERS.computeIfAbsent(version, v -> {
            PostgreSQLContainer server = new PostgreSQLContainer(DockerImageName.parse("postgres:" + v))
                    .withDatabaseName("app")
                    .withUsername("ann")
                    .withPassword("s3cret");
            server.start();
            return server;
        });
    }

    static JdbcConnectionConfig config(PostgreSQLContainer server, String password) {
        return JdbcConnectionConfig.of(
                ReadonlyConfig.of(Map.of("url", server.getJdbcUrl(), "username", "ann", "password", password)),
                DIALECT,
                "src");
    }

    static Connection connect(PostgreSQLContainer server) {
        return JdbcConnections.open(
                config(server, "s3cret"), DIALECT, PostgresDatabaseTest.class.getClassLoader(), "src");
    }

    static void execute(Connection connection, String... statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** Every column type of the table {@code types}, the logical type it maps to and a value. */
    static final Map<String, Object[]> COLUMNS = new LinkedHashMap<>();

    static {
        column("id", "bigint NOT NULL", DataTypes.INT64().notNull(), 1L);
        column("b", "boolean", DataTypes.BOOLEAN(), true);
        column("s", "smallint", DataTypes.INT16(), (short) -32768);
        column("i", "integer", DataTypes.INT32(), Integer.MIN_VALUE);
        column("big", "bigint", DataTypes.INT64(), Long.MIN_VALUE);
        column("r", "real", DataTypes.FLOAT32(), Float.MAX_VALUE);
        column("d", "double precision", DataTypes.FLOAT64(), -Double.MAX_VALUE);
        column("de", "numeric(12,2)", DataTypes.DECIMAL(12, 2), new BigDecimal("1234567890.12"));
        column("n", "numeric", DataTypes.STRING(), "123456789012345678901234567890123456789012345.6789");
        column("nan", "numeric", DataTypes.STRING(), "NaN");
        column("c", "char(4)", DataTypes.STRING(4), "abcd");
        column("v", "varchar(20)", DataTypes.STRING(20), "héllo 世界");
        column("tx", "text", DataTypes.STRING(), "four bytes 😀 and more");
        column("by", "bytea", DataTypes.BYTES(), new byte[] {0, -1, 127, 1});
        column("dt", "date", DataTypes.DATE(), LocalDate.of(9999, 12, 31));
        column("tm", "time(3)", DataTypes.TIME(3), LocalTime.of(23, 59, 59, 999_000_000));
        column("ts", "timestamp(6)", DataTypes.TIMESTAMP(6), LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000));
        column("tz", "timestamptz(3)", DataTypes.TIMESTAMP_LTZ(3), Instant.parse("2100-01-01T00:00:00.123Z"));
        column("j", "jsonb", DataTypes.JSON(), "{\"a\": [1, 2.5, null], \"b\": \"文字\"}");
        column("u", "uuid", DataTypes.STRING(36), "123e4567-e89b-12d3-a456-426614174000");
        column("iv", "interval", DataTypes.STRING(), "1 year 2 mons");
        column("ip", "inet", DataTypes.STRING(), "192.168.0.1/24");
        column("ai", "integer[]", DataTypes.ARRAY(DataTypes.INT32()), Arrays.asList(1, null, -3));
        column("bi", "bigint[]", DataTypes.ARRAY(DataTypes.INT64()), List.of(Long.MAX_VALUE));
        column("at", "text[]", DataTypes.ARRAY(DataTypes.STRING()), List.of("a", "b c", "{}"));
    }

    private static void column(String name, String sql, DataType type, Object value) {
        COLUMNS.put(name, new Object[] {sql, type, value});
    }

    static void createTypes(Connection connection) throws SQLException {
        List<String> columns = new ArrayList<>();
        COLUMNS.forEach((name, spec) -> columns.add(DIALECT.quote(name) + " " + spec[0]));
        execute(
                connection,
                "CREATE SCHEMA IF NOT EXISTS shop",
                "DROP TABLE IF EXISTS shop.types",
                "CREATE TABLE shop.types (" + String.join(", ", columns) + ", PRIMARY KEY (id))");
    }

    @ParameterizedTest(name = "PostgreSQL {0}")
    @ValueSource(strings = {"14", "16"})
    void describesEveryTypeTheWayTheDialectMapsIt(String version) throws Exception {
        try (Connection connection = connect(server(version))) {
            createTypes(connection);
            CatalogTable table =
                    JdbcSchemas.describe(connection, DIALECT, TYPES, "postgres").table();
            assertThat(table.primaryKey()).containsExactly("id");
            assertThat(table.columnNames()).containsExactlyElementsOf(COLUMNS.keySet());
            for (Column column : table.columns()) {
                assertThat(column.type()).as(column.name()).isEqualTo(COLUMNS.get(column.name())[1]);
            }
        }
    }

    @ParameterizedTest(name = "PostgreSQL {0}")
    @ValueSource(strings = {"14", "16"})
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
                DIALECT.write(insert, 1, 2L, types.get(0));
                for (int i = 1; i < names.size(); i++) {
                    DIALECT.write(insert, i + 1, null, types.get(i));
                }
                insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(DIALECT.selectQuery(TYPES, names, null) + " ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                for (int i = 0; i < names.size(); i++) {
                    assertThat(DIALECT.read(rs, i + 1, types.get(i)))
                            .as(names.get(i))
                            .isEqualTo(COLUMNS.get(names.get(i))[2]);
                }
                assertThat(rs.next()).isTrue();
                for (int i = 1; i < names.size(); i++) {
                    assertThat(DIALECT.read(rs, i + 1, types.get(i)))
                            .as(names.get(i))
                            .isNull();
                }
            }
            // the instant was stored as such: a session in Tokyo sees it in its own time
            execute(connection, "SET TIME ZONE 'Asia/Tokyo'");
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT tz::text FROM shop.types WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("2100-01-01 09:00:00.123+09");
            }
        }
    }

    @ParameterizedTest(name = "PostgreSQL {0}")
    @ValueSource(strings = {"14", "16"})
    void streamsLargeResultsInsideATransaction(String version) throws Exception {
        try (Connection connection = connect(server(version))) {
            execute(
                    connection,
                    "CREATE SCHEMA IF NOT EXISTS shop",
                    "DROP TABLE IF EXISTS shop.many",
                    "CREATE TABLE shop.many AS SELECT g AS id FROM generate_series(1, 20000) g");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM shop.many", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                DIALECT.prepareStreaming(connection, statement, 500);
                assertThat(connection.getAutoCommit())
                        .as("the driver streams only in a transaction")
                        .isFalse();
                assertThat(statement.getFetchSize()).isEqualTo(500);
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

    @ParameterizedTest(name = "PostgreSQL {0}")
    @ValueSource(strings = {"14", "16"})
    void classifiesTheErrorsTheServerReports(String version) throws Exception {
        PostgreSQLContainer server = server(version);
        try (Connection connection = connect(server)) {
            execute(
                    connection,
                    "CREATE SCHEMA IF NOT EXISTS shop",
                    "DROP TABLE IF EXISTS shop.keys",
                    "CREATE TABLE shop.keys (id int PRIMARY KEY, name varchar(4) NOT NULL)",
                    "INSERT INTO shop.keys VALUES (1, 'a')");
            assertThat(kind(connection, "INSERT INTO shop.keys VALUES (1, 'b')"))
                    .as("duplicate")
                    .isEqualTo(JdbcErrorKind.DATA);
            assertThat(kind(connection, "INSERT INTO shop.keys VALUES (2, 'too long')"))
                    .as("too long")
                    .isEqualTo(JdbcErrorKind.DATA);
            assertThat(kind(connection, "INSERT INTO shop.keys VALUES (3, NULL)"))
                    .as("NULL")
                    .isEqualTo(JdbcErrorKind.DATA);
            assertThat(kind(connection, "INSERT INTO shop.keys VALUES ('x', 'a')"))
                    .as("bad input")
                    .isEqualTo(JdbcErrorKind.DATA);
            assertThat(kind(connection, "SELECT * FROM shop.nothing")).isEqualTo(JdbcErrorKind.NOT_FOUND);
            assertThat(kind(connection, "SELEKT 1")).isEqualTo(JdbcErrorKind.OTHER);

            // a row another transaction holds: the lock wait times out
            try (Connection holder = connect(server)) {
                holder.setAutoCommit(false);
                execute(holder, "UPDATE shop.keys SET name = 'h' WHERE id = 1");
                execute(connection, "SET lock_timeout = '1s'");
                assertThat(kind(connection, "UPDATE shop.keys SET name = 'w' WHERE id = 1"))
                        .as("lock timeout")
                        .isEqualTo(JdbcErrorKind.TRANSIENT);
                holder.rollback();
            }
        }
        assertThatThrownBy(() -> JdbcConnections.open(
                        config(server, "wrong"), DIALECT, PostgresDatabaseTest.class.getClassLoader(), "src"))
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
