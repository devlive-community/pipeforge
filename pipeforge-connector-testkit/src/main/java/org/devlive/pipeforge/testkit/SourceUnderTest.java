/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.data.TestTable;

/**
 * What the testkit needs to know about a source: how to put a table into the source system and
 * which options make the source read it.
 */
public interface SourceUnderTest {

    SourceFactory factory();

    /**
     * Prepares the source system so that the source reads exactly the rows of {@code table} (e.g.
     * creates and fills a database table), and returns the options of the source node that read it.
     * The source must produce the table with its schema and the rows in any order.
     */
    Map<String, Object> options(TestTable table) throws Exception;

    /** Removes what {@link #options(TestTable)} created; called after every test job. */
    default void cleanup(TestTable table) throws Exception {}

    /** The logical types the source supports; the {@code types} group skips the others with a reason. */
    default Set<TypeRoot> supportedTypes() {
        return EnumSet.allOf(TypeRoot.class);
    }

    default boolean supports(DataType type) {
        return supportedTypes().contains(type.root());
    }

    /** Rows of the large case of the {@code batch} group. */
    default int largeRowCount() {
        return 100_000;
    }

    /**
     * Extra options for each split strategy the source offers, by name; the large cases of the
     * {@code batch} group run once per strategy.
     */
    default Map<String, Map<String, Object>> splitStrategies() {
        return Map.of("default", Map.of());
    }
}
