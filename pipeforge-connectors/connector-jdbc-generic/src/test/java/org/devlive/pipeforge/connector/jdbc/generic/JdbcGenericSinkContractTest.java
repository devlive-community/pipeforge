/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc.generic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.SinkContract;
import org.devlive.pipeforge.testkit.SinkUnderTest;
import org.devlive.pipeforge.testkit.data.TestTable;

/** The testkit's sink groups for the generic JDBC sink, over H2. */
class JdbcGenericSinkContractTest extends SinkContract {

    private final H2 h2 = new H2("generic-sink-contract");

    @Override
    protected SinkUnderTest sink() {
        return new SinkUnderTest() {
            @Override
            public SinkFactory factory() {
                return new JdbcGenericSinkFactory();
            }

            @Override
            public Map<String, Object> options(TestTable table) throws Exception {
                h2.drop(table.table().id());
                h2.create(table.table(), List.of());
                return h2.options(table.table().id());
            }

            @Override
            public Optional<List<Row>> readBack(TestTable table, Map<String, Object> options) throws Exception {
                return Optional.of(h2.rows(table.table()));
            }

            @Override
            public void cleanup(TestTable table, Map<String, Object> options) throws Exception {
                h2.drop(table.table().id());
            }

            @Override
            public Set<TypeRoot> supportedTypes() {
                return JdbcGenericSourceContractTest.TYPES;
            }

            @Override
            public int largeRowCount() {
                return 20_000;
            }
        };
    }
}
