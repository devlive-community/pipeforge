/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.connector.jdbc.AbstractJdbcDialect;
import org.devlive.pipeforge.connector.jdbc.JdbcColumn;
import org.devlive.pipeforge.connector.jdbc.JdbcErrorKind;
import org.jspecify.annotations.Nullable;

/**
 * PostgreSQL 12 to 17 with the PostgreSQL JDBC driver 42.x.
 *
 * <ul>
 *   <li>names are quoted with double quotes; in {@code s.t}, {@code s} is a schema of the database
 *       the URL names, and {@code pg_catalog}, {@code pg_toast} and {@code information_schema}
 *       belong to the system;
 *   <li>{@code timestamptz} is an instant ({@code TIMESTAMP_LTZ}), {@code timestamp} a local time;
 *       {@code json} and {@code jsonb} are {@code JSON}; {@code numeric} without a precision or with
 *       more than 38 digits is {@code STRING}, which keeps every digit and {@code NaN}; {@code uuid},
 *       {@code interval}, {@code money}, network addresses, bit strings, {@code xml} and {@code
 *       timetz} are their text; arrays of numbers, booleans and text are {@code ARRAY};
 *   <li>results stream with the fetch size inside a transaction, which the driver needs to stream;
 *   <li>batches are rewritten into multi-row inserts, and text parameters are sent without a type
 *       so that the server casts them to the column's type ({@code jsonb}, {@code uuid}, enums);
 *       driver options that load classes or write files by name are not allowed;
 *   <li>errors are classified by SQLState, with PostgreSQL's own codes for missing databases,
 *       shutdowns, too many connections and lock timeouts.
 * </ul>
 */
public final class PostgresDialect extends AbstractJdbcDialect {

    private static final long serialVersionUID = 1L;

    /** Types read as their text: exact, and written back the same way. */
    private static final Set<String> TEXT_TYPES = Set.of(
            "uuid",
            "interval",
            "money",
            "inet",
            "cidr",
            "macaddr",
            "macaddr8",
            "bit",
            "varbit",
            "xml",
            "timetz",
            "tsvector",
            "tsquery");

    /** The element types of arrays the dialect reads and writes, by PostgreSQL name. */
    private static final Map<String, DataType> ARRAY_ELEMENTS = Map.of(
            "bool", DataTypes.BOOLEAN(),
            "int2", DataTypes.INT16(),
            "int4", DataTypes.INT32(),
            "int8", DataTypes.INT64(),
            "float4", DataTypes.FLOAT32(),
            "float8", DataTypes.FLOAT64(),
            "text", DataTypes.STRING(),
            "varchar", DataTypes.STRING());

    @Override
    public String name() {
        return "postgres";
    }

    @Override
    public Set<String> systemSchemas() {
        return Set.of("INFORMATION_SCHEMA", "PG_CATALOG", "PG_TOAST");
    }

    @Override
    public Map<String, String> defaultProperties() {
        return Map.of(
                "reWriteBatchedInserts", "true",
                // text parameters take the column's type: jsonb, uuid, enums and the like
                "stringtype", "unspecified");
    }

    @Override
    public Map<String, String> controlledProperties() {
        String loads = "it makes the driver load a class by name on the worker";
        return Map.of(
                "socketFactory", loads,
                "socketFactoryArg", loads,
                "sslfactory", loads,
                "sslfactoryarg", loads,
                "sslhostnameverifier", loads,
                "sslpasswordcallback", loads,
                "authenticationPluginClassName", loads,
                "loggerFile", "it makes the driver write a file of the worker");
    }

    @Override
    public DataType toDataType(JdbcColumn column) {
        String type = column.typeName().toLowerCase(Locale.ROOT);
        DataType mapped = null;
        if (type.equals("timestamptz")) {
            mapped = DataTypes.TIMESTAMP_LTZ(column.scale());
        } else if (type.equals("json") || type.equals("jsonb")) {
            mapped = DataTypes.JSON();
        } else if (type.equals("numeric") && (column.size() <= 0 || column.size() > DataTypes.MAX_DECIMAL_PRECISION)) {
            mapped = DataTypes.STRING();
        } else if (type.equals("uuid")) {
            mapped = DataTypes.STRING(36);
        } else if (TEXT_TYPES.contains(type)) {
            mapped = DataTypes.STRING();
        } else if (type.startsWith("_")) {
            DataType element = ARRAY_ELEMENTS.get(type.substring(1));
            if (element == null) {
                throw new IllegalArgumentException("column '" + column.name() + "' has the type " + column.typeName()
                        + ", an array the postgres dialect cannot read");
            }
            mapped = DataTypes.ARRAY(element);
        }
        if (mapped == null) {
            return super.toDataType(column);
        }
        return column.nullable() ? mapped : mapped.notNull();
    }

    @Override
    public @Nullable Object read(ResultSet rs, int index, DataType type) throws SQLException {
        if (type.root() != TypeRoot.ARRAY) {
            return super.read(rs, index, type);
        }
        Array array = rs.getArray(index);
        if (array == null) {
            return null;
        }
        try {
            Object[] values = (Object[]) array.getArray();
            List<Object> list = new ArrayList<>(values.length);
            for (Object value : values) {
                list.add(element(value, type.elementType()));
            }
            return list;
        } finally {
            array.free();
        }
    }

    /** An element of an array as the engine represents its type. */
    private static @Nullable Object element(@Nullable Object value, DataType type) {
        if (!(value instanceof Number number)) {
            return value;
        }
        return switch (type.root()) {
            case INT16 -> number.shortValue();
            case INT32 -> number.intValue();
            case INT64 -> number.longValue();
            case FLOAT32 -> number.floatValue();
            case FLOAT64 -> number.doubleValue();
            case DECIMAL -> number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
            default -> value;
        };
    }

    @Override
    public void write(PreparedStatement statement, int index, @Nullable Object value, DataType type)
            throws SQLException {
        if (type.root() != TypeRoot.ARRAY) {
            super.write(statement, index, value, type);
            return;
        }
        if (value == null) {
            statement.setNull(index, java.sql.Types.ARRAY);
            return;
        }
        statement.setArray(
                index,
                statement.getConnection().createArrayOf(arrayElement(type.elementType()), ((List<?>) value).toArray()));
    }

    /** The PostgreSQL name of an array's element type. */
    private static String arrayElement(DataType element) {
        return switch (element.root()) {
            case BOOLEAN -> "bool";
            case INT8, INT16 -> "int2";
            case INT32 -> "int4";
            case INT64 -> "int8";
            case FLOAT32 -> "float4";
            case FLOAT64 -> "float8";
            case DECIMAL -> "numeric";
            case STRING -> "text";
            default -> throw new IllegalArgumentException("the postgres dialect cannot write arrays of " + element);
        };
    }

    /** The driver streams a result only inside a transaction, with a fetch size. */
    @Override
    public void prepareStreaming(Connection connection, Statement statement, int fetchSize) throws SQLException {
        if (connection.getAutoCommit()) {
            connection.setAutoCommit(false);
        }
        statement.setFetchSize(fetchSize);
    }

    @Override
    public JdbcErrorKind classify(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            JdbcErrorKind kind = byState(current.getSQLState());
            if (kind != null) {
                return kind;
            }
        }
        return super.classify(error);
    }

    /** PostgreSQL's codes the SQLState classes do not say enough about. */
    private static @Nullable JdbcErrorKind byState(@Nullable String state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
                // no such database, no such schema
            case "3D000", "3F000" -> JdbcErrorKind.NOT_FOUND;
                // shutting down, crashed, starting up, too many connections
            case "57P01", "57P02", "57P03", "53300" -> JdbcErrorKind.CONNECTION;
                // lock not available, statement or lock timeout
            case "55P03", "57014" -> JdbcErrorKind.TRANSIENT;
            default -> null;
        };
    }
}
