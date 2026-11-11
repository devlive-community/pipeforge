/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.data.TestTable;

/** What the testkit needs to know about a sink: where it writes a table and how to read it back. */
public interface SinkUnderTest {

    SinkFactory factory();

    /** Prepares the target system for {@code table} and returns the options of the sink node that write it. */
    Map<String, Object> options(TestTable table) throws Exception;

    /**
     * The rows the sink wrote for {@code table} with {@code options}, in any order; empty when the
     * target cannot be read back (e.g. a sink that discards rows). Then only the job result and the
     * row counters are checked.
     */
    Optional<List<Row>> readBack(TestTable table, Map<String, Object> options) throws Exception;

    /** Removes what the test wrote; called after every test job. */
    default void cleanup(TestTable table, Map<String, Object> options) throws Exception {}

    /** The logical types the sink supports; the {@code types} group skips the others with a reason. */
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
}
