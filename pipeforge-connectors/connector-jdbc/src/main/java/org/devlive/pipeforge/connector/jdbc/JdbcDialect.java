/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.jspecify.annotations.Nullable;

/**
 * What differs between databases: identifiers, SQL, types, driver properties and errors. A plugin
 * extends {@link AbstractJdbcDialect}, which implements the ANSI behavior, and overrides what its
 * database does differently. Dialects are stateless and may be shared.
 */
public interface JdbcDialect extends java.io.Serializable {

    /** The name, e.g. {@code mysql}; it names the target in errors. */
    String name();

    /** {@code identifier} quoted, e.g. {@code "order"} or {@code `order`}. */
    String quote(String identifier);

    /** The qualified, quoted name of a table. */
    String quote(TableId table);

    /**
     * The catalog and the schema JDBC metadata looks a table up in; either may be {@code null}.
     * ANSI: {@code db.t} is schema {@code db}; MySQL: catalog {@code db}.
     */
    String[] catalogAndSchema(TableId table);

    /** The name of a table JDBC metadata lists under a catalog and a schema; ANSI: {@code schema.table}. */
    default TableId tableId(@Nullable String catalog, @Nullable String schema, String table) {
        return schema != null
                ? TableId.of(schema, table)
                : catalog != null ? TableId.of(catalog, table) : TableId.of(table);
    }

    /** Schemas or databases of the system, which {@code tables.include} never matches; in upper case. */
    default java.util.Set<String> systemSchemas() {
        return java.util.Set.of("INFORMATION_SCHEMA");
    }

    /** Driver properties the dialect sets unless the user sets them, e.g. to enable batch rewriting. */
    default Map<String, String> defaultProperties() {
        return Map.of();
    }

    /** Driver properties users may not set, with the reason, e.g. one that breaks transactions. */
    default Map<String, String> controlledProperties() {
        return Map.of();
    }

    /**
     * The logical type of a column.
     *
     * @throws IllegalArgumentException when the dialect cannot read the type; the message names it
     */
    DataType toDataType(JdbcColumn column);

    /** The value of column {@code index} (from 1) of the current row, as the engine represents {@code type}. */
    @Nullable
    Object read(ResultSet rs, int index, DataType type) throws SQLException;

    /** Binds {@code value} of {@code type} to parameter {@code index} (from 1). */
    void write(PreparedStatement statement, int index, @Nullable Object value, DataType type) throws SQLException;

    /** {@code SELECT MIN(column), MAX(column) FROM table}. */
    String minMaxQuery(TableId table, String column);

    /**
     * Selects {@code columns} of {@code table}, with {@code predicate} as the WHERE clause when it is
     * not {@code null}.
     */
    String selectQuery(TableId table, List<String> columns, @Nullable String predicate);

    /** {@code INSERT INTO table (columns) VALUES (?, ...)}. */
    String insertStatement(TableId table, List<String> columns);

    /**
     * Prepares a statement to stream a large result instead of loading it into memory; the ANSI way
     * is a fetch size. Some drivers also need the connection to be in a transaction.
     */
    default void prepareStreaming(Connection connection, Statement statement, int fetchSize) throws SQLException {
        statement.setFetchSize(fetchSize);
    }

    /** What an error means; ANSI looks at the SQLState. */
    JdbcErrorKind classify(SQLException error);
}
