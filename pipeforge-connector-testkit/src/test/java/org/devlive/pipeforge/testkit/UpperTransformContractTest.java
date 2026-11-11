/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;

/** Runs the transform contract on the fixture transform. */
class UpperTransformContractTest extends TransformContract {

    /** The fixture transform with its reference model; also the base of the broken transforms. */
    static class UpperName implements TransformUnderTest {
        @Override
        public TransformFactory factory() {
            return new FixtureUpperFactory();
        }

        @Override
        public Map<String, Object> options() {
            return Map.of("column", "name");
        }

        @Override
        public List<CatalogTable> expectedTables(List<CatalogTable> inputs) {
            return inputs.stream().map(FixtureUpperFactory::output).toList();
        }

        @Override
        public List<Row> expectedRows(CatalogTable table, Row row) {
            return List.of(FixtureUpperFactory.output(table, "name", row));
        }

        @Override
        public Optional<Map<String, Object>> failingOptions() {
            return Optional.of(Map.of("column", "name", "fail", true));
        }
    }

    @Override
    protected TransformUnderTest transform() {
        return new UpperName();
    }
}
