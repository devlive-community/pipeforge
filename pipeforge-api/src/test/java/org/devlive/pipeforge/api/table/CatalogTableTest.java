/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.type.DataTypes;
import org.junit.jupiter.api.Test;

class CatalogTableTest {

    private static final TableId ID = TableId.of("shop", "orders");

    private static CatalogTable orders() {
        return CatalogTable.of(
                        ID,
                        List.of(
                                Column.of("id", DataTypes.INT64().notNull()),
                                Column.of("amount", DataTypes.DECIMAL(12, 2)),
                                Column.of("day", DataTypes.DATE())))
                .withPrimaryKey("id");
    }

    @Test
    void describesColumnsAndKeys() {
        CatalogTable table = orders();
        assertThat(table.columnNames()).containsExactly("id", "amount", "day");
        assertThat(table.indexOf("amount")).isEqualTo(1);
        assertThat(table.indexOf("missing")).isEqualTo(-1);
        assertThat(table.column("day")).map(Column::type).contains(DataTypes.DATE());
        assertThat(table.primaryKey()).containsExactly("id");
        assertThat(table.withId(TableId.of("x")).id()).isEqualTo(TableId.of("x"));
    }

    @Test
    void dropsKeysOfRemovedColumns() {
        CatalogTable table = new CatalogTable(ID, orders().columns(), List.of("id"), List.of("day"), Map.of(), "c");
        CatalogTable narrowed = table.withColumns(List.of(Column.of("amount", DataTypes.DECIMAL(12, 2))));
        assertThat(narrowed.primaryKey()).isEmpty();
        assertThat(narrowed.partitionKeys()).isEmpty();
        assertThat(narrowed.comment()).isEqualTo("c");
    }

    @Test
    void rejectsInconsistentDefinitions() {
        Column id = Column.of("id", DataTypes.INT64());
        assertThatThrownBy(() -> CatalogTable.of(ID, List.of(id, id))).hasMessageContaining("Duplicate column 'id'");
        assertThatThrownBy(() -> CatalogTable.of(ID, List.of(id)).withPrimaryKey("nope"))
                .hasMessageContaining("Primary key column 'nope'");
        assertThatThrownBy(() -> new CatalogTable(ID, List.of(id), List.of(), List.of("p"), Map.of(), null))
                .hasMessageContaining("Partition column 'p'");
        assertThatThrownBy(() -> Column.of("", DataTypes.INT32())).hasMessageContaining("must not be empty");
    }

    @Test
    void columnsAreImmutableValues() {
        Column column = Column.of("a", DataTypes.INT32());
        assertThat(column.withName("b").name()).isEqualTo("b");
        assertThat(column.withType(DataTypes.INT64()).type()).isEqualTo(DataTypes.INT64());
        assertThat(column.withComment("note").comment()).isEqualTo("note");
    }

    @Test
    void schemaChangesNameTheirTable() {
        TableId other = TableId.of("x");
        List<SchemaChangeEvent> events = List.of(
                new CreateTable(orders()),
                new DropTable(ID),
                new TruncateTable(ID),
                new RenameTable(ID, other),
                new AddColumn(ID, Column.of("note", DataTypes.STRING()), "amount"),
                new DropColumn(ID, "note"),
                new RenameColumn(ID, "amount", "total"),
                new AlterColumnType(ID, "amount", DataTypes.DECIMAL(14, 2)),
                new AlterColumnComment(ID, "amount", "sum"));
        assertThat(events).allMatch(e -> e.table().equals(ID));
    }
}
