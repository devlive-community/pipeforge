/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc.generic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.connector.jdbc.GenericDialect;

/** An in-memory H2 database for the tests: tables from catalog tables, rows in and out. */
final class H2 {

    static final GenericDialect DIALECT = new GenericDialect();

    final String url;

    H2(String name) {
        this.url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    /** The options that connect a node to this database. */
    Map<String, Object> options(TableId table) {
        return Map.of("url", url, "username", "sa", "password", "", "table", table.toQualifiedName());
    }

    void execute(String... statements) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** The H2 type of a logical type. */
    static String ddl(DataType type) {
        String sql =
                switch (type.root()) {
                    case BOOLEAN -> "BOOLEAN";
                    case INT8 -> "TINYINT";
                    case INT16 -> "SMALLINT";
                    case INT32 -> "INTEGER";
                    case INT64 -> "BIGINT";
                    case FLOAT32 -> "REAL";
                    case FLOAT64 -> "DOUBLE PRECISION";
                    case DECIMAL -> "NUMERIC(" + type.precision() + ", " + type.scale() + ")";
                    case STRING -> type.length() != null ? "VARCHAR(" + type.length() + ")" : "VARCHAR";
                    case BYTES -> "VARBINARY";
                    case DATE -> "DATE";
                    case TIME -> "TIME(" + type.precision() + ")";
                    case TIMESTAMP -> "TIMESTAMP(" + type.precision() + ")";
                    case TIMESTAMP_LTZ -> "TIMESTAMP(" + type.precision() + ") WITH TIME ZONE";
                    default -> throw new IllegalArgumentException("no H2 type for " + type);
                };
        return type.isNullable() ? sql : sql + " NOT NULL";
    }

    /** Creates {@code table}, with its schema, and inserts {@code rows}. */
    void create(CatalogTable table, List<Row> rows) throws SQLException {
        String columns = table.columns().stream()
                .map(c -> DIALECT.quote(c.name()) + " " + ddl(c.type()))
                .collect(Collectors.joining(", "));
        String key = table.primaryKey().isEmpty()
                ? ""
                : ", PRIMARY KEY ("
                        + table.primaryKey().stream().map(DIALECT::quote).collect(Collectors.joining(", ")) + ")";
        List<String> statements = new ArrayList<>();
        if (table.id().database() != null) {
            statements.add(
                    "CREATE SCHEMA IF NOT EXISTS " + DIALECT.quote(table.id().database()));
        }
        statements.add("CREATE TABLE " + DIALECT.quote(table.id()) + " (" + columns + key + ")");
        execute(statements.toArray(String[]::new));
        insert(table, rows);
    }

    void insert(CatalogTable table, List<Row> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        try (Connection connection = connect();
                PreparedStatement insert =
                        connection.prepareStatement(DIALECT.insertStatement(table.id(), table.columnNames()))) {
            for (Row row : rows) {
                for (int i = 0; i < table.columns().size(); i++) {
                    DIALECT.write(
                            insert, i + 1, row.get(i), table.columns().get(i).type());
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** The rows of {@code table}, read with the types of its columns. */
    List<Row> rows(CatalogTable table) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(DIALECT.selectQuery(table.id(), table.columnNames(), null))) {
            while (rs.next()) {
                Object[] values = new Object[table.columns().size()];
                for (int i = 0; i < values.length; i++) {
                    Column column = table.columns().get(i);
                    values[i] = DIALECT.read(rs, i + 1, column.type());
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
}
