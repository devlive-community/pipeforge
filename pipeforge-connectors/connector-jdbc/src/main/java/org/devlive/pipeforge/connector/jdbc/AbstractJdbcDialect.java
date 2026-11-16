/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.jspecify.annotations.Nullable;

/**
 * The ANSI SQL behavior of a dialect: double-quoted identifiers, {@code db.t} as schema and table,
 * the standard JDBC types and the SQLState classes of errors. Dialects override what their
 * database does differently.
 */
public abstract class AbstractJdbcDialect implements JdbcDialect {

    /** Strings at least this long are unbounded; drivers report their maximum for unbounded types. */
    static final int UNBOUNDED_LENGTH = 1_000_000_000;

    /** The widest DECIMAL. */
    static final int MAX_PRECISION = 38;

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String quote(TableId table) {
        StringBuilder name = new StringBuilder();
        for (String part : new String[] {table.catalog(), table.database(), table.schema()}) {
            if (part != null) {
                name.append(quote(part)).append('.');
            }
        }
        return name.append(quote(table.table())).toString();
    }

    @Override
    public String[] catalogAndSchema(TableId table) {
        String schema = table.schema() != null ? table.schema() : table.database();
        String catalog = table.schema() != null ? table.database() : table.catalog();
        return new String[] {catalog, schema};
    }

    @Override
    public DataType toDataType(JdbcColumn column) {
        DataType type =
                switch (column.sqlType()) {
                    case Types.BIT, Types.BOOLEAN -> DataTypes.BOOLEAN();
                    case Types.TINYINT -> DataTypes.INT8();
                    case Types.SMALLINT -> DataTypes.INT16();
                    case Types.INTEGER -> DataTypes.INT32();
                    case Types.BIGINT -> DataTypes.INT64();
                    case Types.REAL -> DataTypes.FLOAT32();
                    case Types.FLOAT, Types.DOUBLE -> DataTypes.FLOAT64();
                    case Types.NUMERIC, Types.DECIMAL -> decimal(column.size(), column.scale());
                    case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR -> bounded(column.size())
                            ? DataTypes.STRING(column.size())
                            : DataTypes.STRING();
                    case Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> DataTypes.STRING();
                    case Types.BINARY, Types.VARBINARY -> bounded(column.size())
                            ? DataTypes.BYTES(column.size())
                            : DataTypes.BYTES();
                    case Types.LONGVARBINARY, Types.BLOB -> DataTypes.BYTES();
                    case Types.DATE -> DataTypes.DATE();
                    case Types.TIME, Types.TIME_WITH_TIMEZONE -> DataTypes.TIME(column.scale());
                    case Types.TIMESTAMP -> DataTypes.TIMESTAMP(column.scale());
                    case Types.TIMESTAMP_WITH_TIMEZONE -> DataTypes.TIMESTAMP_LTZ(column.scale());
                    default -> throw new IllegalArgumentException("column '" + column.name() + "' has the type "
                            + column.typeName() + ", which the " + name() + " dialect cannot read");
                };
        return column.nullable() ? type : type.notNull();
    }

    private static boolean bounded(int size) {
        return size > 0 && size < UNBOUNDED_LENGTH;
    }

    private static DataType decimal(int precision, int scale) {
        if (precision <= 0 || precision > MAX_PRECISION) {
            // a NUMERIC without precision: as much as fits
            return DataTypes.DECIMAL(MAX_PRECISION, Math.max(0, Math.min(scale, MAX_PRECISION)));
        }
        return DataTypes.DECIMAL(precision, Math.max(0, Math.min(scale, precision)));
    }

    @Override
    public @Nullable Object read(ResultSet rs, int index, DataType type) throws SQLException {
        Object value =
                switch (type.root()) {
                    case BOOLEAN -> rs.getBoolean(index);
                    case INT8 -> rs.getByte(index);
                    case INT16 -> rs.getShort(index);
                    case INT32 -> rs.getInt(index);
                    case INT64 -> rs.getLong(index);
                    case FLOAT32 -> rs.getFloat(index);
                    case FLOAT64 -> rs.getDouble(index);
                    case DECIMAL -> {
                        BigDecimal decimal = rs.getBigDecimal(index);
                        yield decimal == null
                                ? null
                                : decimal.setScale(type.scale(), java.math.RoundingMode.UNNECESSARY);
                    }
                    case STRING, JSON -> rs.getString(index);
                    case BYTES -> rs.getBytes(index);
                    case DATE -> rs.getObject(index, LocalDate.class);
                    case TIME -> rs.getObject(index, LocalTime.class);
                    case TIMESTAMP -> rs.getObject(index, LocalDateTime.class);
                    case TIMESTAMP_LTZ -> {
                        OffsetDateTime time = rs.getObject(index, OffsetDateTime.class);
                        yield time == null ? null : time.toInstant();
                    }
                    default -> rs.getObject(index);
                };
        return rs.wasNull() ? null : value;
    }

    @Override
    public void write(PreparedStatement statement, int index, @Nullable Object value, DataType type)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, sqlType(type));
            return;
        }
        switch (type.root()) {
            case BOOLEAN -> statement.setBoolean(index, (Boolean) value);
            case INT8 -> statement.setByte(index, (Byte) value);
            case INT16 -> statement.setShort(index, (Short) value);
            case INT32 -> statement.setInt(index, (Integer) value);
            case INT64 -> statement.setLong(index, (Long) value);
            case FLOAT32 -> statement.setFloat(index, (Float) value);
            case FLOAT64 -> statement.setDouble(index, (Double) value);
            case DECIMAL -> statement.setBigDecimal(index, (BigDecimal) value);
            case STRING, JSON -> statement.setString(index, (String) value);
            case BYTES -> statement.setBytes(index, (byte[]) value);
            case TIMESTAMP_LTZ -> statement.setObject(index, ((Instant) value).atOffset(ZoneOffset.UTC));
            default -> statement.setObject(index, value);
        }
    }

    /** The {@link Types} code of a logical type, for NULLs. */
    protected int sqlType(DataType type) {
        return switch (type.root()) {
            case BOOLEAN -> Types.BOOLEAN;
            case INT8 -> Types.TINYINT;
            case INT16 -> Types.SMALLINT;
            case INT32 -> Types.INTEGER;
            case INT64 -> Types.BIGINT;
            case FLOAT32 -> Types.REAL;
            case FLOAT64 -> Types.DOUBLE;
            case DECIMAL -> Types.DECIMAL;
            case STRING, JSON -> Types.VARCHAR;
            case BYTES -> Types.VARBINARY;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMESTAMP -> Types.TIMESTAMP;
            case TIMESTAMP_LTZ -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> Types.OTHER;
        };
    }

    @Override
    public String minMaxQuery(TableId table, String column) {
        String quoted = quote(column);
        return "SELECT MIN(" + quoted + "), MAX(" + quoted + ") FROM " + quote(table);
    }

    @Override
    public String selectQuery(TableId table, List<String> columns, @Nullable String predicate) {
        String select = "SELECT " + columns.stream().map(this::quote).collect(Collectors.joining(", ")) + " FROM "
                + quote(table);
        return predicate == null ? select : select + " WHERE " + predicate;
    }

    @Override
    public String insertStatement(TableId table, List<String> columns) {
        return "INSERT INTO " + quote(table) + " ("
                + columns.stream().map(this::quote).collect(Collectors.joining(", ")) + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
    }

    @Override
    public JdbcErrorKind classify(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            JdbcErrorKind kind = classifyOne(current);
            if (kind != JdbcErrorKind.OTHER) {
                return kind;
            }
        }
        return JdbcErrorKind.OTHER;
    }

    private JdbcErrorKind classifyOne(SQLException error) {
        if (error instanceof SQLTimeoutException) {
            return JdbcErrorKind.TRANSIENT;
        }
        if (error instanceof SQLTransientConnectionException) {
            return JdbcErrorKind.CONNECTION;
        }
        String state = error.getSQLState();
        if (state == null || state.length() < 2) {
            return JdbcErrorKind.OTHER;
        }
        if (state.equals("40001") || state.equals("40P01") || state.startsWith("HYT")) {
            // serialization failure, deadlock, timeout
            return JdbcErrorKind.TRANSIENT;
        }
        if (state.equals("42S02") || state.equals("42P01") || state.equals("42S22") || state.equals("42703")) {
            return JdbcErrorKind.NOT_FOUND;
        }
        return switch (state.substring(0, 2)) {
            case "08" -> JdbcErrorKind.CONNECTION;
            case "28" -> JdbcErrorKind.AUTH;
            case "22", "23" -> JdbcErrorKind.DATA;
            default -> JdbcErrorKind.OTHER;
        };
    }
}
