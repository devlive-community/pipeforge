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
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.TestTable;

/** The testkit's own sink passes the sink contract. */
class CollectSinkContractTest extends SinkContract {

    /** The testkit sink as a sink under test; also the base of the broken sinks of other tests. */
    static class CollectSink implements SinkUnderTest {
        @Override
        public SinkFactory factory() {
            return TestkitConnectors.collect();
        }

        @Override
        public Map<String, Object> options(TestTable table) {
            return Map.of("dataset", Datasets.output());
        }

        @Override
        public Optional<List<Row>> readBack(TestTable table, Map<String, Object> options) {
            return Optional.of(Datasets.written((String) options.get("dataset")));
        }

        @Override
        public void cleanup(TestTable table, Map<String, Object> options) {
            Datasets.release((String) options.get("dataset"));
        }

        @Override
        public int largeRowCount() {
            return 20_000;
        }
    }

    @Override
    protected SinkUnderTest sink() {
        return new CollectSink();
    }
}
