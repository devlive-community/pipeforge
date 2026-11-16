/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.SourceContract;
import org.devlive.pipeforge.testkit.SourceUnderTest;
import org.devlive.pipeforge.testkit.data.TestTable;

/** The testkit's source groups over H2 with the generic dialect. */
class JdbcSourceContractTest extends SourceContract {

    /** The types of the ANSI dialect; arrays, maps, rows and JSON need a dialect of their own. */
    static final Set<TypeRoot> TYPES =
            EnumSet.complementOf(EnumSet.of(TypeRoot.ARRAY, TypeRoot.MAP, TypeRoot.ROW, TypeRoot.JSON, TypeRoot.NULL));

    private final H2 h2 = new H2("source-contract");

    @Override
    protected SourceUnderTest source() {
        return new SourceUnderTest() {
            @Override
            public SourceFactory factory() {
                return new TestFactories.Source();
            }

            @Override
            public Map<String, Object> options(TestTable table) throws Exception {
                h2.drop(table.table().id());
                h2.create(table.table(), table.rows());
                return h2.options(table.table().id());
            }

            @Override
            public void cleanup(TestTable table) throws Exception {
                h2.drop(table.table().id());
            }

            @Override
            public Set<TypeRoot> supportedTypes() {
                return TYPES;
            }

            @Override
            public int largeRowCount() {
                return 20_000;
            }

            @Override
            public Map<String, Map<String, Object>> splitStrategies() {
                return Map.of(
                        "one split", Map.of("split.mode", "none"),
                        "ranges of the primary key", Map.of("split.count", 16));
            }
        };
    }
}
