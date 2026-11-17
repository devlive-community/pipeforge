/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.connector.jdbc.JdbcConnectionConfig;
import org.devlive.pipeforge.connector.jdbc.JdbcConnections;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Tables of the tests on a PostgreSQL server: created from catalog tables, filled and read back. */
final class Postgres {

    private static final PostgresDialect DIALECT = new PostgresDialect();

    private final PostgreSQLContainer server;

    Postgres(String version) {
        this.server = PostgresDatabaseTest.server(version);
    }

    /** The options that connect a node to {@code table}. */
    Map<String, Object> options(TableId table) {
        return Map.of(
                "url", server.getJdbcUrl(),
                "username", "ann",
                "password", server.getPassword(),
                "table", table.toQualifiedName());
    }

    Connection connect() {
        return PostgresDatabaseTest.connect(server);
    }

    void execute(String... statements) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** The PostgreSQL type a test uses for a logical type. */
    static String ddl(DataType type) {
        String sql =
                switch (type.root()) {
                    case BOOLEAN -> "boolean";
                    case INT8, INT16 -> "smallint";
                    case INT32 -> "integer";
                    case INT64 -> "bigint";
                    case FLOAT32 -> "real";
                    case FLOAT64 -> "double precision";
                    case DECIMAL -> "numeric(" + type.precision() + ", " + type.scale() + ")";
                    case STRING -> type.length() != null ? "varchar(" + type.length() + ")" : "text";
                    case BYTES -> "bytea";
                    case DATE -> "date";
                    case TIME -> "time(" + Math.min(6, type.precision()) + ")";
                    case TIMESTAMP -> "timestamp(" + Math.min(6, type.precision()) + ")";
                    case TIMESTAMP_LTZ -> "timestamptz(" + Math.min(6, type.precision()) + ")";
                    case JSON -> "jsonb";
                    case ARRAY -> ddl(type.elementType().withNullability(true)) + "[]";
                    default -> throw new IllegalArgumentException("no PostgreSQL type for " + type);
                };
        return type.isNullable() ? sql : sql + " NOT NULL";
    }

    /** Creates {@code table} and its schema, and inserts {@code rows}. */
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
            int batch = 0;
            for (Row row : rows) {
                for (int i = 0; i < table.columns().size(); i++) {
                    DIALECT.write(
                            insert, i + 1, row.get(i), table.columns().get(i).type());
                }
                insert.addBatch();
                if (++batch == 1000) {
                    insert.executeBatch();
                    batch = 0;
                }
            }
            if (batch > 0) {
                insert.executeBatch();
            }
        }
    }

    /** The rows of {@code table}, read with the dialect. */
    List<Row> rows(CatalogTable table) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(DIALECT.selectQuery(table.id(), table.columnNames(), null))) {
            while (rs.next()) {
                Object[] values = new Object[table.columns().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = DIALECT.read(rs, i + 1, table.columns().get(i).type());
                }
                rows.add(GenericRow.of(table.id(), values));
            }
        }
        return rows;
    }

    void drop(TableId table) throws SQLException {
        if (table.database() != null) {
            execute("CREATE SCHEMA IF NOT EXISTS " + DIALECT.quote(table.database()));
        }
        execute("DROP TABLE IF EXISTS " + DIALECT.quote(table));
    }

    static JdbcConnectionConfig config(String url, String password) {
        return JdbcConnectionConfig.of(
                ReadonlyConfig.of(Map.of("url", url, "username", "ann", "password", password)), DIALECT, "test");
    }

    static Connection open(JdbcConnectionConfig config) {
        return JdbcConnections.open(config, DIALECT, Postgres.class.getClassLoader(), "test");
    }
}
