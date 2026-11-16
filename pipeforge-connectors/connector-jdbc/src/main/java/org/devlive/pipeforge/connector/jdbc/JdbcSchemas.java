/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/** Describes tables and queries from JDBC metadata, with the types of the dialect. */
public final class JdbcSchemas {

    private JdbcSchemas() {}

    /**
     * A table as the database has it.
     *
     * @param table the table under the name the user gave, with its columns and primary key
     * @param physical the name as the database stores it, for SQL
     */
    public record Described(CatalogTable table, TableId physical) {}

    /**
     * Describes {@code table}. A name that is not found as written is looked up in upper and in
     * lower case, since databases store unquoted names in one of them.
     *
     * @throws PipeForgeException {@code PF-META-0001} when it does not exist, {@code PF-DATA-0001}
     *     for a column the dialect cannot read
     */
    public static Described describe(Connection connection, JdbcDialect dialect, TableId table, String target)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (TableId candidate : List.of(table, recase(table, true), recase(table, false))) {
            String[] place = dialect.catalogAndSchema(candidate);
            List<JdbcColumn> columns = new ArrayList<>();
            try (ResultSet rs = metadata.getColumns(place[0], place[1], candidate.table(), null)) {
                Map<Integer, JdbcColumn> ordered = new TreeMap<>();
                while (rs.next()) {
                    ordered.put(
                            rs.getInt("ORDINAL_POSITION"),
                            new JdbcColumn(
                                    rs.getString("COLUMN_NAME"),
                                    rs.getInt("DATA_TYPE"),
                                    String.valueOf(rs.getString("TYPE_NAME")),
                                    rs.getInt("COLUMN_SIZE"),
                                    rs.getInt("DECIMAL_DIGITS"),
                                    rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
                }
                columns.addAll(ordered.values());
            }
            if (columns.isEmpty()) {
                continue;
            }
            Map<Short, String> keys = new TreeMap<>();
            try (ResultSet rs = metadata.getPrimaryKeys(place[0], place[1], candidate.table())) {
                while (rs.next()) {
                    keys.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                }
            }
            CatalogTable described = CatalogTable.of(table, columns(dialect, table, columns));
            if (!keys.isEmpty()) {
                described = described.withPrimaryKey(keys.values().toArray(String[]::new));
            }
            return new Described(described, candidate);
        }
        throw PipeForgeException.of(ErrorCode.TABLE_NOT_FOUND)
                .param("table", table.toQualifiedName())
                .param("target", target)
                .build();
    }

    /** Describes the result of {@code query} without reading it; the table is named {@code id}. */
    public static CatalogTable describeQuery(Connection connection, JdbcDialect dialect, String query, TableId id)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT * FROM (" + query + ") pipeforge_query WHERE 1 = 0")) {
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData metadata = rs.getMetaData();
                List<JdbcColumn> columns = new ArrayList<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    columns.add(new JdbcColumn(
                            metadata.getColumnLabel(i),
                            metadata.getColumnType(i),
                            String.valueOf(metadata.getColumnTypeName(i)),
                            metadata.getPrecision(i),
                            metadata.getScale(i),
                            metadata.isNullable(i) != ResultSetMetaData.columnNoNulls));
                }
                return CatalogTable.of(id, columns(dialect, id, columns));
            }
        }
    }

    private static List<Column> columns(JdbcDialect dialect, TableId table, List<JdbcColumn> columns) {
        List<Column> result = new ArrayList<>(columns.size());
        for (JdbcColumn column : columns) {
            try {
                result.add(Column.of(column.name(), dialect.toDataType(column)));
            } catch (IllegalArgumentException e) {
                throw PipeForgeException.of(ErrorCode.TYPE_CONVERSION)
                        .param("column", table.toQualifiedName() + "." + column.name())
                        .param("type", column.typeName())
                        .param("reason", String.valueOf(e.getMessage()))
                        .build();
            }
        }
        return result;
    }

    private static TableId recase(TableId table, boolean upper) {
        return new TableId(
                recase(table.catalog(), upper),
                recase(table.database(), upper),
                recase(table.schema(), upper),
                recase(table.table(), upper));
    }

    private static @Nullable String recase(@Nullable String name, boolean upper) {
        if (name == null) {
            return null;
        }
        return upper ? name.toUpperCase(Locale.ROOT) : name.toLowerCase(Locale.ROOT);
    }
}
