/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.devlive.pipeforge.api.type.DataTypes.DECIMAL;
import static org.devlive.pipeforge.api.type.DataTypes.INT32;
import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;
import static org.devlive.pipeforge.api.type.DataTypes.TIMESTAMP_LTZ;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.RowKind;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.AddColumn;
import org.devlive.pipeforge.api.table.AlterColumnComment;
import org.devlive.pipeforge.api.table.AlterColumnType;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.table.CreateTable;
import org.devlive.pipeforge.api.table.DropColumn;
import org.devlive.pipeforge.api.table.DropTable;
import org.devlive.pipeforge.api.table.RenameColumn;
import org.devlive.pipeforge.api.table.RenameTable;
import org.devlive.pipeforge.api.table.TruncateTable;
import org.junit.jupiter.api.Test;

class ConsoleWriterTest {

    static final TableId ORDERS = TableId.of("shop", "orders");
    static final CatalogTable TABLE = CatalogTable.of(
            ORDERS,
            List.of(
                    Column.of("id", INT64().notNull()),
                    Column.of("name", STRING()),
                    Column.of("amount", DECIMAL(10, 2))));

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private ConsoleWriter writer(
            ConsoleOptions.Format format, long limit, boolean kind, int batch, int width, int index, int parallelism) {
        return new ConsoleWriter(
                new ConsoleSettings(format, ConsoleOptions.Target.STDOUT, limit, kind, batch, width),
                List.of(TABLE),
                new Values(ZoneId.of("Asia/Shanghai")),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                index,
                parallelism);
    }

    private ConsoleWriter table() {
        return writer(ConsoleOptions.Format.TABLE, -1, true, 20, 40, 0, 1);
    }

    private ConsoleWriter json() {
        return writer(ConsoleOptions.Format.JSON, -1, true, 20, 40, 0, 1);
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static GenericRow order(long id, String name, String amount) {
        return GenericRow.of(ORDERS, id, name, amount == null ? null : new BigDecimal(amount));
    }

    @Test
    void printsAlignedTablesWithTheRowKind() throws Exception {
        try (ConsoleWriter writer = table()) {
            writer.write(order(1, "Ada", "12.50"));
            writer.write(order(22, "林", null).withKind(RowKind.DELETE));
            assertThat(output()).as("rows wait for the batch size").isEmpty();
        }
        assertThat(output())
                .isEqualTo(
                        """
                        shop.orders
                        +----+----+------+--------+
                        | op | id | name | amount |
                        +----+----+------+--------+
                        | +I |  1 | Ada  |  12.50 |
                        | -D | 22 | 林   |   NULL |
                        +----+----+------+--------+
                        """);
    }

    @Test
    void printsATableEveryBatchAndAtEachFlush() throws Exception {
        ConsoleWriter writer = writer(ConsoleOptions.Format.TABLE, -1, false, 2, 40, 1, 4);
        writer.write(order(1, "a", "1.00"));
        writer.write(order(2, "b", "2.00"));
        assertThat(output()).startsWith("shop.orders [subtask 2/4]\n").contains("| id | name | amount |");
        writer.write(order(3, "c", "3.00"));
        writer.flush();
        writer.flush();
        writer.close();
        assertThat(output().split("shop.orders", -1)).hasSize(3);
        assertThat(output()).doesNotContain("| op |");
    }

    @Test
    void cutsLongValuesAndEscapesLineBreaks() throws Exception {
        try (ConsoleWriter writer = writer(ConsoleOptions.Format.TABLE, -1, false, 20, 8, 0, 1)) {
            writer.write(order(1, "line one\nline two", "1.00"));
            writer.write(order(2, "汉字汉字汉字", "1.00"));
        }
        // 8 columns: 7 characters and the ellipsis; a wide character counts as two columns.
        assertThat(output()).contains("| line on… |").contains("| 汉字汉…  |");
        try (ConsoleWriter writer = writer(ConsoleOptions.Format.TABLE, -1, false, 20, 0, 0, 1)) {
            writer.write(order(3, "a\tb\u0001", "1.00"));
        }
        assertThat(output()).contains("| a\\tb\\u0001 |");
    }

    @Test
    void writesOneJsonObjectPerLine() throws Exception {
        try (ConsoleWriter writer = json()) {
            writer.write(order(1, "Ada \"A\"", "12.50"));
            writer.write(order(2, null, "0.10").withKind(RowKind.UPDATE_AFTER));
        }
        assertThat(output())
                .isEqualTo(
                        """
                        {"table":"shop.orders","kind":"+I","row":{"id":1,"name":"Ada \\"A\\"","amount":12.50}}
                        {"table":"shop.orders","kind":"+U","row":{"id":2,"name":null,"amount":0.10}}
                        """);
        bytes.reset();
        try (ConsoleWriter writer = writer(ConsoleOptions.Format.JSON, -1, false, 20, 3, 0, 1)) {
            writer.write(order(3, "never cut", "1.00"));
        }
        assertThat(output())
                .isEqualTo("{\"table\":\"shop.orders\",\"row\":{\"id\":3,\"name\":\"never cut\",\"amount\":1.00}}\n");
    }

    @Test
    void stopsPrintingAtTheLimitAndReportsWhatWasLeftOut() throws Exception {
        ConsoleWriter writer = writer(ConsoleOptions.Format.TABLE, 2, true, 20, 40, 2, 3);
        for (int i = 0; i < 5; i++) {
            writer.write(order(i, "n" + i, "1.00"));
        }
        writer.close();
        assertThat(writer.printed()).isEqualTo(2);
        assertThat(writer.skipped()).isEqualTo(3);
        assertThat(output())
                .isEqualTo(
                        """
                        shop.orders [subtask 3/3]
                        +----+----+------+--------+
                        | op | id | name | amount |
                        +----+----+------+--------+
                        | +I |  0 | n0   |   1.00 |
                        | +I |  1 | n1   |   1.00 |
                        +----+----+------+--------+
                        -- limit of 2 row(s) reached in subtask 3/3; further rows are counted but not printed
                        -- 3 row(s) not printed in subtask 3/3 (limit 2)
                        """);

        bytes.reset();
        ConsoleWriter zero = writer(ConsoleOptions.Format.JSON, 0, true, 20, 40, 0, 1);
        zero.write(order(1, "a", "1.00"));
        zero.close();
        assertThat(output())
                .isEqualTo(
                        """
                        {"notice":"limit of 0 row(s) reached; further rows are counted but not printed"}
                        {"notice":"1 row(s) not printed (limit 0)"}
                        """);
    }

    @Test
    void followsSchemaChanges() throws Exception {
        ConsoleWriter writer = table();
        writer.write(order(1, "a", "1.00"));
        writer.applySchemaChange(new AddColumn(ORDERS, Column.of("qty", INT32()), "id"));
        writer.write(GenericRow.of(ORDERS, 2L, 5, "b", new BigDecimal("2.00")));
        writer.applySchemaChange(new RenameColumn(ORDERS, "qty", "quantity"));
        writer.applySchemaChange(new AlterColumnType(ORDERS, "quantity", INT64()));
        writer.applySchemaChange(new DropColumn(ORDERS, "name"));
        writer.applySchemaChange(new AlterColumnComment(ORDERS, "id", "the key"));
        writer.write(GenericRow.of(ORDERS, 3L, 7L, new BigDecimal("3.00")));
        writer.applySchemaChange(new TruncateTable(ORDERS));
        writer.applySchemaChange(new DropColumn(ORDERS, "missing"));
        writer.close();
        String text = output();
        assertThat(text)
                .contains("-- shop.orders: add column qty INT32 after id\n")
                .contains("| op | id | qty | name | amount |")
                .contains("-- shop.orders: rename column qty to quantity\n")
                .contains("-- shop.orders: change column quantity to INT64\n")
                .contains("-- shop.orders: drop column name\n")
                .contains("-- shop.orders: change the comment of column id\n")
                .contains("| op | id | quantity | amount |")
                .contains("-- shop.orders: truncate table\n")
                .contains("-- shop.orders: drop column missing\n");
        // The first row was printed before the change, with the columns it had.
        assertThat(text.indexOf("| op | id | name | amount |")).isLessThan(text.indexOf("add column"));
    }

    @Test
    void followsTableChangesAndPrintsUnknownTables() throws Exception {
        TableId items = TableId.of("shop", "items");
        TableId renamed = TableId.of("shop", "orders_v2");
        ConsoleWriter writer = json();
        writer.applySchemaChange(new CreateTable(CatalogTable.of(items, List.of(Column.of("sku", STRING())))));
        writer.write(GenericRow.of(items, "x-1"));
        writer.applySchemaChange(new RenameTable(ORDERS, renamed));
        writer.write(GenericRow.of(renamed, 1L, "a", new BigDecimal("1.00")));
        writer.applySchemaChange(new DropTable(items));
        writer.write(GenericRow.of(items, "x-2", 3));
        writer.applySchemaChange(new RenameTable(TableId.of("nowhere"), TableId.of("still_nowhere")));
        writer.close();
        assertThat(output())
                .contains("{\"table\":\"shop.items\",\"schema-change\":\"create table with 1 column(s)\"}\n")
                .contains("{\"table\":\"shop.items\",\"kind\":\"+I\",\"row\":{\"sku\":\"x-1\"}}\n")
                .contains("{\"table\":\"shop.orders\",\"schema-change\":\"rename table to shop.orders_v2\"}\n")
                .contains("\"table\":\"shop.orders_v2\",\"kind\":\"+I\",\"row\":{\"id\":1,\"name\":\"a\"")
                .contains("{\"table\":\"shop.items\",\"schema-change\":\"drop table\"}\n")
                // A table the sink does not know gets columns c1, c2, ... typed by their values.
                .contains("{\"table\":\"shop.items\",\"kind\":\"+I\",\"row\":{\"c1\":\"x-2\",\"c2\":3}}\n");
    }

    @Test
    void unknownTablesGetNumberedColumnsInTables() throws Exception {
        try (ConsoleWriter writer = table()) {
            writer.write(GenericRow.of(TableId.of("other"), 1L, Instant.parse("2026-01-01T00:00:00Z")));
            writer.write(GenericRow.of(ORDERS, 1L, "a", new BigDecimal("1.00"), "extra"));
        }
        // Columns of unknown type are left-aligned, and times get the fractional digits they need.
        assertThat(output())
                .contains("| op | c1 | c2 ")
                .contains("| +I | 1  | 2026-01-01 08:00:00+08:00 |")
                .contains("| op | id | name | amount | c4    |")
                .contains("| +I |  1 | a    |   1.00 | extra |");
    }

    @Test
    void rendersLtzColumnsInTheJobTimeZone() throws Exception {
        CatalogTable events = CatalogTable.of(TableId.of("events"), List.of(Column.of("at", TIMESTAMP_LTZ(3))));
        ConsoleWriter writer = new ConsoleWriter(
                new ConsoleSettings(ConsoleOptions.Format.TABLE, ConsoleOptions.Target.STDOUT, -1, false, 20, 40),
                List.of(events),
                new Values(ZoneId.of("Asia/Shanghai")),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                0,
                1);
        writer.write(GenericRow.of(TableId.of("events"), Instant.parse("2026-11-07T04:30:00.25Z")));
        writer.close();
        assertThat(output()).contains("| 2026-11-07 12:30:00.250+08:00 |");
    }
}
