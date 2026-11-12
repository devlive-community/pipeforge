/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.blackhole;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.testkit.SinkContract;
import org.devlive.pipeforge.testkit.SinkUnderTest;
import org.devlive.pipeforge.testkit.data.TestTable;

/** The testkit contract: rows cannot be read back, so the job results and the row counters are checked. */
class BlackholeSinkContractTest extends SinkContract {

    @Override
    protected SinkUnderTest sink() {
        return new SinkUnderTest() {
            @Override
            public SinkFactory factory() {
                return new BlackholeSinkFactory();
            }

            @Override
            public Map<String, Object> options(TestTable table) {
                return Map.of();
            }

            @Override
            public Optional<List<Row>> readBack(TestTable table, Map<String, Object> options) {
                return Optional.empty();
            }
        };
    }
}
