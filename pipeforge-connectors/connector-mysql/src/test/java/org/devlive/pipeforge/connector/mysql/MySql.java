/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.connector.jdbc.JdbcConnectionConfig;
import org.devlive.pipeforge.connector.jdbc.JdbcConnections;
import org.testcontainers.mysql.MySQLContainer;

/** Tables of the tests on a MySQL server, as its root user: created from catalog tables and filled. */
final class MySql {

    private static final MySqlDialect DIALECT = new MySqlDialect();

    private final MySQLContainer server;

    MySql(String version) {
        this.server = MySqlDatabaseTest.server(version);
    }

    String url() {
        return server.getJdbcUrl();
    }

    /** A connection with the dialect's session settings, as the source has: UTC, so TIMESTAMP values keep their instant. */
    Connection connect() {
        return JdbcConnections.open(
                JdbcConnectionConfig.of(
                        ReadonlyConfig.of(Map.of("url", url(), "username", "root", "password", server.getPassword())),
                        DIALECT,
                        "test"),
                DIALECT,
                MySql.class.getClassLoader(),
                "test");
    }

    /** The options that connect a source node to {@code table}. */
    Map<String, Object> options(TableId table) {
        return Map.of(
                "url", url(), "username", "root", "password", server.getPassword(), "table", table.toQualifiedName());
    }

    void execute(String... statements) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** The MySQL type a test uses for a logical type. */
    static String ddl(DataType type) {
        String sql =
                switch (type.root()) {
                    case BOOLEAN -> "BOOLEAN";
                    case INT8 -> "TINYINT";
                    case INT16 -> "SMALLINT";
                    case INT32 -> "INT";
                    case INT64 -> "BIGINT";
                    case FLOAT32 -> "FLOAT";
                    case FLOAT64 -> "DOUBLE";
                    case DECIMAL -> "DECIMAL(" + type.precision() + ", " + type.scale() + ")";
                    case STRING -> type.length() != null ? "VARCHAR(" + type.length() + ")" : "LONGTEXT";
                    case BYTES -> type.length() != null ? "VARBINARY(" + type.length() + ")" : "LONGBLOB";
                    case DATE -> "DATE";
                    case TIME -> "TIME(" + Math.min(6, type.precision()) + ")";
                    case TIMESTAMP -> "DATETIME(" + Math.min(6, type.precision()) + ")";
                    case TIMESTAMP_LTZ -> "TIMESTAMP(" + Math.min(6, type.precision()) + ") NULL";
                    case JSON -> "JSON";
                    default -> throw new IllegalArgumentException("no MySQL type for " + type);
                };
        return type.isNullable() ? sql : sql + " NOT NULL";
    }

    /** Creates {@code table} and its database, and inserts {@code rows}. */
    void create(CatalogTable table, List<Row> rows) throws SQLException {
        String columns = table.columns().stream()
                .map(c -> DIALECT.quote(c.name()) + " " + ddl(c.type()))
                .collect(Collectors.joining(", "));
        String key = table.primaryKey().isEmpty()
                ? ""
                : ", PRIMARY KEY ("
                        + table.primaryKey().stream().map(DIALECT::quote).collect(Collectors.joining(", ")) + ")";
        drop(table.id());
        execute("CREATE TABLE " + DIALECT.quote(table.id()) + " (" + columns + key + ")");
        insert(table, rows);
    }

    void insert(CatalogTable table, List<Row> rows) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement insert =
                        connection.prepareStatement(DIALECT.insertStatement(table.id(), table.columnNames()))) {
            List<Row> batch = new ArrayList<>();
            for (Row row : rows) {
                for (int i = 0; i < table.columns().size(); i++) {
                    DIALECT.write(
                            insert, i + 1, row.get(i), table.columns().get(i).type());
                }
                insert.addBatch();
                batch.add(row);
                if (batch.size() == 1000) {
                    insert.executeBatch();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                insert.executeBatch();
            }
        }
    }

    /** The rows of {@code table}, read the way the source reads them: over the binary protocol. */
    List<Row> rows(CatalogTable table) throws SQLException {
        MySqlDialect reading = MySqlDialect.forReading();
        List<Row> rows = new ArrayList<>();
        try (Connection connection = JdbcConnections.open(
                        JdbcConnectionConfig.of(
                                ReadonlyConfig.of(
                                        Map.of("url", url(), "username", "root", "password", server.getPassword())),
                                reading,
                                "test"),
                        reading,
                        MySql.class.getClassLoader(),
                        "test");
                PreparedStatement select =
                        connection.prepareStatement(reading.selectQuery(table.id(), table.columnNames(), null));
                java.sql.ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                Object[] values = new Object[table.columns().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = reading.read(rs, i + 1, table.columns().get(i).type());
                }
                rows.add(org.devlive.pipeforge.api.row.GenericRow.of(table.id(), values));
            }
        }
        return rows;
    }

    /** Whether MySQL can store {@code value}: FLOAT and TIMESTAMP have narrower ranges than their types. */
    static boolean holds(DataType type, Object value) {
        if (value instanceof Float f) {
            // MySQL's FLOAT ends at 3.402823466E+38, just below Float.MAX_VALUE
            return Math.abs(f) < Float.MAX_VALUE;
        }
        if (value instanceof java.time.Instant instant) {
            return !instant.isBefore(FIRST_TIMESTAMP) && !instant.isAfter(LAST_TIMESTAMP);
        }
        return true;
    }

    /** The range of a MySQL TIMESTAMP. */
    static final java.time.Instant FIRST_TIMESTAMP = java.time.Instant.parse("1970-01-01T00:00:01Z");

    static final java.time.Instant LAST_TIMESTAMP = java.time.Instant.parse("2038-01-19T03:14:07.999999Z");

    void drop(TableId table) throws SQLException {
        if (table.database() != null) {
            execute("CREATE DATABASE IF NOT EXISTS " + DIALECT.quote(table.database()));
        }
        execute("DROP TABLE IF EXISTS " + DIALECT.quote(table));
    }
}
