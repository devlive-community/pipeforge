/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.devlive.pipeforge.transform.fieldmapper.Fixtures.ORDERS;
import static org.devlive.pipeforge.transform.fieldmapper.Fixtures.TABLE;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.Row;
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
import org.devlive.pipeforge.api.transform.TransformContext;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class FieldMapperTransformTest {

    /** A field mapper that renames id, upper-cases names, drops password and writes the rest in snake case. */
    private static FieldMapperTransform open() {
        FieldMapperTransform transform = new FieldMapperTransform(
                Fixtures.config(Map.of(
                        "mapping",
                        List.of(
                                Map.of("from", "id", "to", "order_id"),
                                Map.of("expr", "UPPER(customerName)", "to", "loud")),
                        "drop",
                        List.of("password"),
                        "name-case",
                        "snake")),
                Fixtures.EXPRESSIONS);
        transform.open(new TransformContext() {
            @Override
            public JobContext job() {
                return JobContext.local("test");
            }

            @Override
            public int subtaskIndex() {
                return 0;
            }

            @Override
            public int parallelism() {
                return 1;
            }

            @Override
            public List<CatalogTable> inputTables() {
                return List.of(TABLE);
            }

            @Override
            public ClassLoader classLoader() {
                return getClass().getClassLoader();
            }

            @Override
            public ExpressionCompiler expressions() {
                return Fixtures.EXPRESSIONS;
            }
        });
        return transform;
    }

    private static List<Row> process(FieldMapperTransform transform, Row row) {
        List<Row> out = new ArrayList<>();
        transform.process(row, out::add);
        return out;
    }

    @Test
    void createdByTheFactoryWithTheOptionsOfTheNode() {
        FieldMapperFactory factory = new FieldMapperFactory();
        assertThat(factory.identifier()).isEqualTo("field-mapper");
        assertThat(factory.optionRule().options())
                .extracting(o -> o.key())
                .containsExactly("mapping", "drop", "others", "unmapped", "name-case", "order", "on-error");
        FieldMapperTransform transform = new FieldMapperTransform(
                MapperConfig.read(ReadonlyConfig.of(Map.of("name-case", "upper")), "map"), Fixtures.EXPRESSIONS);
        assertThat(transform.transformSchema(List.of(TABLE)).get(0).columnNames())
                .containsExactly("ID", "CUSTOMERNAME", "AMOUNT", "CREATED_AT", "PAID", "PASSWORD");
    }

    @Test
    void mapsRowsOfEveryInputTable() {
        FieldMapperTransform transform = open();
        List<Row> rows = process(transform, Fixtures.row(3, "lin", "9.99"));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).arity()).isEqualTo(6);
        assertThat(rows.get(0).get(0)).isEqualTo(3L);
        assertThat(rows.get(0).get(5)).isEqualTo("LIN");
    }

    @Test
    void passesColumnChangesOnUnderTheirOutputNames() {
        FieldMapperTransform transform = open();
        // the kept column follows the other kept columns, before the computed column loud
        assertThat(transform.transformSchemaChange(
                        new AddColumn(ORDERS, Column.of("shipTo", DataTypes.STRING()), null)))
                .isEqualTo(new AddColumn(ORDERS, Column.of("ship_to", DataTypes.STRING()), "paid"));
        Row wider = org.devlive.pipeforge.api.row.GenericRow.of(ORDERS, 4L, "kim", null, null, null, "x", "Berlin");
        Row mapped = process(transform, wider).get(0);
        assertThat(mapped.get(5)).isEqualTo("Berlin");
        assertThat(mapped.get(6)).isEqualTo("KIM");

        assertThat(transform.transformSchemaChange(new AlterColumnType(ORDERS, "amount", DataTypes.DECIMAL(12, 2))))
                .isEqualTo(new AlterColumnType(ORDERS, "amount", DataTypes.DECIMAL(12, 2)));
        assertThat(transform.transformSchemaChange(new AlterColumnComment(ORDERS, "created_at", "when")))
                .isEqualTo(new AlterColumnComment(ORDERS, "created_at", "when"));
        assertThat(transform.transformSchemaChange(new RenameColumn(ORDERS, "paid", "isPaid")))
                .isEqualTo(new RenameColumn(ORDERS, "paid", "is_paid"));
        assertThat(transform.transformSchemaChange(new DropColumn(ORDERS, "isPaid")))
                .isEqualTo(new DropColumn(ORDERS, "is_paid"));
    }

    @Test
    void swallowsChangesOfColumnsThatDoNotReachTheOutput() {
        FieldMapperTransform transform = open();
        assertThat(transform.transformSchemaChange(new AlterColumnType(ORDERS, "password", DataTypes.STRING(64))))
                .isNull();
        assertThat(transform.transformSchemaChange(new DropColumn(ORDERS, "password")))
                .isNull();
    }

    @Test
    void failsWhenAChangeTouchesAColumnTheMappingReads() {
        FieldMapperTransform transform = open();
        PipeForgeException error = catchThrowableOfType(
                PipeForgeException.class,
                () -> transform.transformSchemaChange(new DropColumn(ORDERS, "customerName")));
        assertThat(error.code()).isEqualTo(ErrorCode.SCHEMA_INCOMPATIBLE);
        assertThat(error.params())
                .containsEntry("node", "map")
                .containsEntry("upstream", "shop.orders")
                .containsEntry("reason", "the schema change drops column customerName, which the mapping reads");
        PipeForgeException middle = catchThrowableOfType(
                PipeForgeException.class,
                () -> transform.transformSchemaChange(new AddColumn(ORDERS, Column.of("x", DataTypes.INT32()), "id")));
        assertThat(middle.params().get("reason")).asString().contains("only be added at the end");
    }

    @Test
    void mapsNewAndRenamedTables() {
        FieldMapperTransform transform = open();
        TableId archive = TableId.of("shop", "orders_2025");
        CreateTable created = (CreateTable) transform.transformSchemaChange(new CreateTable(TABLE.withId(archive)));
        assertThat(created.definition().columnNames())
                .containsExactly("order_id", "customer_name", "amount", "created_at", "paid", "loud");
        assertThat(transform.transformSchemaChange(new RenameTable(archive, TableId.of("shop", "old"))))
                .isEqualTo(new RenameTable(archive, TableId.of("shop", "old")));
        assertThat(process(transform, Fixtures.row(TableId.of("shop", "old"), 1, "x", "1.00")))
                .hasSize(1);
        assertThat(transform.transformSchemaChange(new DropTable(ORDERS))).isEqualTo(new DropTable(ORDERS));
    }
}
