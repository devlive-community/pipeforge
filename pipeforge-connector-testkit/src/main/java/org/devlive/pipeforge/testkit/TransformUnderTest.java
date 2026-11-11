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
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.testkit.data.TestData;

/**
 * What the testkit needs to know about a transform: the options to test it with and a reference
 * model of what it should produce. The {@code transform} group runs it between the testkit's source
 * and sink and compares the result with the model.
 */
public interface TransformUnderTest {

    TransformFactory factory();

    /** The options of the transform node; the input tables are those of {@link #input()}, or several of them. */
    Map<String, Object> options();

    /** The table the cases read; by default {@link TestData#wideTable()}. */
    default CatalogTable input() {
        return TestData.wideTable();
    }

    /** The tables the transform should derive from {@code inputs} while planning. */
    List<CatalogTable> expectedTables(List<CatalogTable> inputs);

    /** The rows the transform should emit for {@code row} of {@code table}, in order. */
    List<Row> expectedRows(CatalogTable table, Row row);

    /**
     * The schema change the transform should pass on for {@code event} on a table of {@code
     * inputs}, or empty when it should drop it; by default the event unchanged.
     */
    default Optional<SchemaChangeEvent> expectedSchemaChange(List<CatalogTable> inputs, SchemaChangeEvent event) {
        return Optional.of(event);
    }

    /**
     * Options with which processing a row fails, e.g. an expression that divides by zero; the
     * {@code transform} group checks that the job fails with a registered error code at the node.
     * Empty when no option makes rows fail; the case is then skipped.
     */
    default Optional<Map<String, Object>> failingOptions() {
        return Optional.empty();
    }

    /** Whether the transform handles several input tables; when not, that case is skipped. */
    default boolean supportsMultipleTables() {
        return true;
    }
}
