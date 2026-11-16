/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.List;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.junit.jupiter.api.Test;

class GenericDialectTest {

    private static final GenericDialect DIALECT = new GenericDialect();

    private static DataType type(int sqlType, int size, int scale, boolean nullable) {
        return DIALECT.toDataType(new JdbcColumn("c", sqlType, "T", size, scale, nullable));
    }

    @Test
    void quotesNamesAndPlacesTables() {
        assertThat(DIALECT.quote("order")).isEqualTo("\"order\"");
        assertThat(DIALECT.quote("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(DIALECT.quote(TableId.parse("shop.orders"))).isEqualTo("\"shop\".\"orders\"");
        assertThat(DIALECT.catalogAndSchema(TableId.parse("shop.orders"))).containsExactly(null, "shop");
        assertThat(DIALECT.catalogAndSchema(TableId.parse("db.public.orders"))).containsExactly("db", "public");
        assertThat(DIALECT.catalogAndSchema(TableId.of("orders"))).containsExactly(null, null);
    }

    @Test
    void writesTheSqlOfReadsAndWrites() {
        TableId table = TableId.parse("shop.orders");
        assertThat(DIALECT.minMaxQuery(table, "id"))
                .isEqualTo("SELECT MIN(\"id\"), MAX(\"id\") FROM \"shop\".\"orders\"");
        assertThat(DIALECT.selectQuery(table, List.of("id", "name"), "\"id\" < ?"))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"shop\".\"orders\" WHERE \"id\" < ?");
        assertThat(DIALECT.selectQuery(table, List.of("id"), null)).isEqualTo("SELECT \"id\" FROM \"shop\".\"orders\"");
        assertThat(DIALECT.insertStatement(table, List.of("id", "name")))
                .isEqualTo("INSERT INTO \"shop\".\"orders\" (\"id\", \"name\") VALUES (?, ?)");
    }

    @Test
    void mapsTheStandardTypes() {
        assertThat(type(Types.BIT, 1, 0, true)).isEqualTo(DataTypes.BOOLEAN());
        assertThat(type(Types.TINYINT, 3, 0, true)).isEqualTo(DataTypes.INT8());
        assertThat(type(Types.SMALLINT, 5, 0, true)).isEqualTo(DataTypes.INT16());
        assertThat(type(Types.INTEGER, 10, 0, false))
                .isEqualTo(DataTypes.INT32().notNull());
        assertThat(type(Types.BIGINT, 19, 0, true)).isEqualTo(DataTypes.INT64());
        assertThat(type(Types.REAL, 7, 0, true)).isEqualTo(DataTypes.FLOAT32());
        assertThat(type(Types.FLOAT, 15, 0, true)).isEqualTo(DataTypes.FLOAT64());
        assertThat(type(Types.DECIMAL, 12, 2, true)).isEqualTo(DataTypes.DECIMAL(12, 2));
        assertThat(type(Types.NUMERIC, 0, 0, true)).as("without precision").isEqualTo(DataTypes.DECIMAL(38, 0));
        assertThat(type(Types.NUMERIC, 65, 30, true)).as("wider than 38").isEqualTo(DataTypes.DECIMAL(38, 30));
        assertThat(type(Types.VARCHAR, 255, 0, true)).isEqualTo(DataTypes.STRING(255));
        assertThat(type(Types.VARCHAR, 1_000_000_000, 0, true)).as("unbounded").isEqualTo(DataTypes.STRING());
        assertThat(type(Types.CLOB, 0, 0, true)).isEqualTo(DataTypes.STRING());
        assertThat(type(Types.VARBINARY, 16, 0, true)).isEqualTo(DataTypes.BYTES(16));
        assertThat(type(Types.BLOB, 0, 0, true)).isEqualTo(DataTypes.BYTES());
        assertThat(type(Types.DATE, 10, 0, true)).isEqualTo(DataTypes.DATE());
        assertThat(type(Types.TIME, 12, 3, true)).isEqualTo(DataTypes.TIME(3));
        assertThat(type(Types.TIMESTAMP, 26, 6, true)).isEqualTo(DataTypes.TIMESTAMP(6));
        assertThat(type(Types.TIMESTAMP_WITH_TIMEZONE, 32, 6, true)).isEqualTo(DataTypes.TIMESTAMP_LTZ(6));
        assertThatThrownBy(() -> DIALECT.toDataType(new JdbcColumn("geo", Types.OTHER, "GEOMETRY", 0, 0, true)))
                .hasMessage("column 'geo' has the type GEOMETRY, which the jdbc dialect cannot read");
    }

    @Test
    void classifiesErrorsByTheirState() {
        assertThat(DIALECT.classify(new SQLException("refused", "08001"))).isEqualTo(JdbcErrorKind.CONNECTION);
        assertThat(DIALECT.classify(new SQLException("login", "28000"))).isEqualTo(JdbcErrorKind.AUTH);
        assertThat(DIALECT.classify(new SQLException("unique", "23505"))).isEqualTo(JdbcErrorKind.DATA);
        assertThat(DIALECT.classify(new SQLException("too long", "22001"))).isEqualTo(JdbcErrorKind.DATA);
        assertThat(DIALECT.classify(new SQLException("deadlock", "40001"))).isEqualTo(JdbcErrorKind.TRANSIENT);
        assertThat(DIALECT.classify(new SQLException("deadlock", "40P01"))).isEqualTo(JdbcErrorKind.TRANSIENT);
        assertThat(DIALECT.classify(new SQLTimeoutException("slow"))).isEqualTo(JdbcErrorKind.TRANSIENT);
        assertThat(DIALECT.classify(new SQLException("no table", "42S02"))).isEqualTo(JdbcErrorKind.NOT_FOUND);
        assertThat(DIALECT.classify(new SQLException("syntax", "42000"))).isEqualTo(JdbcErrorKind.OTHER);
        assertThat(DIALECT.classify(new SQLException("no state"))).isEqualTo(JdbcErrorKind.OTHER);
        // a batch error says little; the exception chained to it says why
        BatchUpdateException batch = new BatchUpdateException("batch failed", "HY000", new int[0]);
        batch.setNextException(new SQLException("duplicate key", "23505"));
        assertThat(DIALECT.classify(batch)).isEqualTo(JdbcErrorKind.DATA);
    }
}
