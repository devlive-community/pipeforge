/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.AddColumn;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.testkit.TransformContract;
import org.devlive.pipeforge.testkit.TransformUnderTest;

/**
 * The testkit contract of the field mapper on the testkit's wide table ({@code id}, {@code name},
 * {@code amount}, {@code created_at}, {@code active}): a rename that keeps the key, a computed and
 * retyped column, an expression column, a constant, a drop and upper-case names for the rest.
 */
class FieldMapperContractTest extends TransformContract {

    private static final Map<String, Object> OPTIONS = Map.of(
            "mapping",
            List.of(
                    Map.of("from", "id", "to", "item_id"),
                    Map.of("from", "amount", "to", "cents", "expr", "amount * 100", "type", "DECIMAL(16, 2)"),
                    Map.of("expr", "LOWER(name)", "to", "quiet"),
                    Map.of("value", "testkit", "to", "origin")),
            "drop",
            List.of("active"),
            "name-case",
            "upper");

    @Override
    protected TransformUnderTest transform() {
        return new TransformUnderTest() {
            @Override
            public TransformFactory factory() {
                return new FieldMapperFactory();
            }

            @Override
            public Map<String, Object> options() {
                return OPTIONS;
            }

            @Override
            public List<CatalogTable> expectedTables(List<CatalogTable> inputs) {
                List<CatalogTable> out = new ArrayList<>();
                for (CatalogTable table : inputs) {
                    out.add(new CatalogTable(
                            table.id(),
                            List.of(
                                    Column.of("item_id", DataTypes.INT64().notNull()),
                                    Column.of("NAME", DataTypes.STRING()),
                                    Column.of("cents", DataTypes.DECIMAL(16, 2)),
                                    Column.of("CREATED_AT", DataTypes.TIMESTAMP(3)),
                                    Column.of("quiet", DataTypes.STRING()),
                                    Column.of("origin", DataTypes.STRING().notNull())),
                            List.of("item_id"),
                            List.of(),
                            table.options(),
                            table.comment()));
                }
                return out;
            }

            @Override
            public List<Row> expectedRows(CatalogTable table, Row row) {
                String name = (String) row.get(1);
                BigDecimal amount = (BigDecimal) row.get(2);
                return List.of(GenericRow.ofKind(
                        row.table(),
                        row.kind(),
                        row.get(0),
                        name,
                        amount == null
                                ? null
                                : amount.multiply(BigDecimal.valueOf(100)).setScale(2),
                        row.get(3),
                        name == null ? null : name.toLowerCase(Locale.ROOT),
                        "testkit"));
            }

            @Override
            public Optional<SchemaChangeEvent> expectedSchemaChange(
                    List<CatalogTable> inputs, SchemaChangeEvent event) {
                // kept columns keep the input order, so the new column follows CREATED_AT, before quiet
                AddColumn add = (AddColumn) event;
                return Optional.of(new AddColumn(
                        add.table(),
                        add.column().withName(add.column().name().toUpperCase(Locale.ROOT)),
                        "CREATED_AT"));
            }

            @Override
            public Optional<Map<String, Object>> failingOptions() {
                return Optional.of(Map.of(
                        "mapping", List.of(Map.of("expr", "CAST(name AS INT64)", "to", "number")), "on-error", "fail"));
            }
        };
    }
}
