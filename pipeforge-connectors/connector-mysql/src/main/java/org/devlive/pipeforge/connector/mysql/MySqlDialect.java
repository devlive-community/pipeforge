/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.mysql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.connector.jdbc.AbstractJdbcDialect;
import org.devlive.pipeforge.connector.jdbc.JdbcColumn;
import org.devlive.pipeforge.connector.jdbc.JdbcErrorKind;
import org.jspecify.annotations.Nullable;

/**
 * MySQL 5.7, 8.0 and 8.4 with MySQL Connector/J 8.x.
 *
 * <ul>
 *   <li>names are quoted with backticks; in {@code db.t}, {@code db} is a database, which JDBC
 *       calls a catalog;
 *   <li>unsigned integers take the next wider type ({@code BIGINT UNSIGNED} a {@code
 *       DECIMAL(20, 0)}), {@code YEAR} is {@code INT16}, {@code BIT(n)} with more than one bit
 *       {@code BYTES}, {@code JSON} is {@code JSON}, and {@code TIMESTAMP}, which MySQL stores in UTC,
 *       is an instant ({@code TIMESTAMP_LTZ}) while {@code DATETIME} is a local time;
 *   <li>results stream row by row (a fetch size of {@code Integer.MIN_VALUE}), the way Connector/J
 *       streams;
 *   <li>batches are rewritten into multi-row inserts, text is UTF-8, {@code YEAR} a number and the
 *       session is in UTC, so that instants do not depend on the time zones of the server and the
 *       worker, unless the user says otherwise; loading local files and reconnecting on its own are not allowed;
 *   <li>errors are classified by MySQL's error codes where the SQLState says too little.
 * </ul>
 *
 * <p>The dialect of a source ({@link #forReading()}) also reads over the binary protocol, with
 * server-side prepared statements: the text protocol prints a {@code FLOAT} with six significant
 * digits, which loses most of its precision.
 */
public final class MySqlDialect extends AbstractJdbcDialect {

    private static final long serialVersionUID = 1L;

    private final boolean reading;

    /** The dialect of a sink and of the tools: client-side prepared statements, batches rewritten. */
    public MySqlDialect() {
        this(false);
    }

    private MySqlDialect(boolean reading) {
        this.reading = reading;
    }

    /** The dialect of a source, which reads over the binary protocol so that FLOAT keeps its value. */
    public static MySqlDialect forReading() {
        return new MySqlDialect(true);
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String quote(TableId table) {
        String database = table.database() != null ? table.database() : table.schema();
        return database == null ? quote(table.table()) : quote(database) + "." + quote(table.table());
    }

    @Override
    public String[] catalogAndSchema(TableId table) {
        return new String[] {table.database() != null ? table.database() : table.schema(), null};
    }

    /** Metadata lists MySQL tables under their database, as the catalog. */
    @Override
    public TableId tableId(@Nullable String catalog, @Nullable String schema, String table) {
        return catalog != null ? TableId.of(catalog, table) : TableId.of(table);
    }

    @Override
    public Set<String> systemSchemas() {
        return Set.of("INFORMATION_SCHEMA", "MYSQL", "PERFORMANCE_SCHEMA", "SYS");
    }

    @Override
    public Map<String, String> defaultProperties() {
        if (reading) {
            Map<String, String> properties = new java.util.LinkedHashMap<>(writing());
            // values in binary: the text protocol prints FLOAT with six significant digits
            properties.put("useServerPrepStmts", "true");
            return Map.copyOf(properties);
        }
        return writing();
    }

    private static Map<String, String> writing() {
        return Map.of(
                "rewriteBatchedStatements", "true",
                "characterEncoding", "UTF-8",
                // YEAR as a number, not as a date of January 1st
                "yearIsDateType", "false",
                // TIMESTAMP values are instants whatever the time zones of the server and the worker
                "connectionTimeZone", "UTC",
                "forceConnectionTimeZoneToSession", "true");
    }

    @Override
    public Map<String, String> controlledProperties() {
        return Map.of(
                "allowLoadLocalInfile", "it lets the database server read files of the worker",
                "allowUrlInLocalInfile", "it lets the database server read files of the worker",
                "autoReconnect",
                        "a reconnect in the middle of a batch would lose its transaction; the writer reconnects itself");
    }

    @Override
    public DataType toDataType(JdbcColumn column) {
        String type = column.typeName().toUpperCase(Locale.ROOT);
        boolean unsigned = type.contains("UNSIGNED");
        DataType mapped = null;
        if (type.startsWith("TINYINT") && unsigned) {
            mapped = DataTypes.INT16();
        } else if ((type.startsWith("SMALLINT") || type.startsWith("MEDIUMINT")) && unsigned) {
            mapped = DataTypes.INT32();
        } else if (type.startsWith("MEDIUMINT")) {
            mapped = DataTypes.INT32();
        } else if (type.startsWith("INT") && unsigned) {
            mapped = DataTypes.INT64();
        } else if (type.startsWith("BIGINT") && unsigned) {
            mapped = DataTypes.DECIMAL(20, 0);
        } else if (type.equals("YEAR")) {
            mapped = DataTypes.INT16();
        } else if (type.equals("BIT") && column.size() > 1) {
            mapped = DataTypes.BYTES();
        } else if (type.equals("JSON")) {
            mapped = DataTypes.JSON();
        } else if (type.equals("TIMESTAMP")) {
            mapped = DataTypes.TIMESTAMP_LTZ(fraction(column, 19));
        } else if (type.equals("DATETIME")) {
            mapped = DataTypes.TIMESTAMP(fraction(column, 19));
        } else if (type.equals("TIME")) {
            mapped = DataTypes.TIME(fraction(column, 8));
        }
        if (mapped == null) {
            return super.toDataType(column);
        }
        return column.nullable() ? mapped : mapped.notNull();
    }

    /**
     * The fractional digits of a time type. Connector/J reports them only in the display size: {@code
     * hh:mm:ss.fff} is 12 for a {@code TIME(3)}, whose whole part is 8 characters.
     */
    private static int fraction(JdbcColumn column, int whole) {
        if (column.scale() > 0) {
            return column.scale();
        }
        return column.size() > whole + 1 ? Math.min(9, column.size() - whole - 1) : 0;
    }

    /** Connector/J streams a result row by row only with this fetch size; otherwise it loads it all. */
    @Override
    public void prepareStreaming(Connection connection, Statement statement, int fetchSize) throws SQLException {
        statement.setFetchSize(Integer.MIN_VALUE);
    }

    @Override
    public JdbcErrorKind classify(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            JdbcErrorKind kind = byCode(current.getErrorCode());
            if (kind != null) {
                return kind;
            }
        }
        return super.classify(error);
    }

    /** MySQL's error codes, for the errors whose SQLState is the generic HY000. */
    private static @Nullable JdbcErrorKind byCode(int code) {
        return switch (code) {
                // lock wait timeout, deadlock, too many connections, gone away, lost connection
            case 1205, 1213 -> JdbcErrorKind.TRANSIENT;
            case 1040, 1053, 2002, 2003, 2006, 2013 -> JdbcErrorKind.CONNECTION;
                // access denied
            case 1044, 1045, 1698 -> JdbcErrorKind.AUTH;
                // unknown database, table, column
            case 1049, 1146, 1054 -> JdbcErrorKind.NOT_FOUND;
                // incorrect value, truncated datetime, out of range, too long, NULL, no default, duplicate, foreign key
            case 1366, 1292, 1264, 1406, 1048, 1364, 1062, 1451, 1452, 3819 -> JdbcErrorKind.DATA;
            default -> null;
        };
    }
}
