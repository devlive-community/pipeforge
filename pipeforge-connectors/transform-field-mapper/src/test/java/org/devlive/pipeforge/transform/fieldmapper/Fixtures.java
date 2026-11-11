/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import static org.devlive.pipeforge.api.type.DataTypes.BOOLEAN;
import static org.devlive.pipeforge.api.type.DataTypes.DECIMAL;
import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;
import static org.devlive.pipeforge.api.type.DataTypes.TIMESTAMP;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.core.expression.EngineExpressions;

/** The orders table of the unit tests and helpers around it. */
final class Fixtures {

    static final TableId ORDERS = TableId.of("shop", "orders");
    static final EngineExpressions EXPRESSIONS = EngineExpressions.planning(ZoneId.of("UTC"));

    static final CatalogTable TABLE = CatalogTable.of(
                    ORDERS,
                    List.of(
                            Column.of("id", INT64().notNull()),
                            Column.of("customerName", STRING(40)).withComment("who ordered"),
                            Column.of("amount", DECIMAL(10, 2)),
                            Column.of("created_at", TIMESTAMP(3)),
                            Column.of("paid", BOOLEAN()),
                            Column.of("password", STRING())))
            .withPrimaryKey("id");

    private Fixtures() {}

    static MapperConfig config(Map<String, ?> options) {
        return MapperConfig.read(ReadonlyConfig.of(options), "map");
    }

    static TableMapping mapping(Map<String, ?> options) {
        return TableMapping.derive(config(options), TABLE, EXPRESSIONS);
    }

    static Row row(long id, String name, String amount) {
        return row(ORDERS, id, name, amount);
    }

    static Row row(TableId table, long id, String name, String amount) {
        return GenericRow.of(
                table,
                id,
                name,
                amount == null ? null : new BigDecimal(amount),
                LocalDateTime.parse("2026-11-07T08:30:00"),
                Boolean.TRUE,
                "secret");
    }
}
