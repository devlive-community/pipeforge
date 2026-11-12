/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.example.upper;

import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.testkit.TransformContract;
import org.devlive.pipeforge.testkit.TransformUnderTest;

/** The testkit contract of the transform. */
class UpperContractTest extends TransformContract {

    @Override
    protected TransformUnderTest transform() {
        return new TransformUnderTest() {
            @Override
            public TransformFactory factory() {
                return new UpperFactory();
            }

            @Override
            public Map<String, Object> options() {
                return Map.of("column", "name");
            }

            @Override
            public List<CatalogTable> expectedTables(List<CatalogTable> inputs) {
                return inputs;
            }

            @Override
            public List<Row> expectedRows(CatalogTable table, Row row) {
                return List.of(UpperFactory.upper(table, "name", row));
            }
        };
    }
}
