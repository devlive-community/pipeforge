/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.Map;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.TestTable;

/** The testkit's own source passes the source contract. */
class RowsSourceContractTest extends SourceContract {

    /** The testkit source as a source under test; also the base of the broken sources of other tests. */
    static class RowsSource implements SourceUnderTest {
        @Override
        public SourceFactory factory() {
            return TestkitConnectors.rows();
        }

        @Override
        public Map<String, Object> options(TestTable table) {
            return Map.of("dataset", Datasets.input(table), "splits", 7);
        }

        @Override
        public int largeRowCount() {
            return 20_000;
        }

        @Override
        public Map<String, Map<String, Object>> splitStrategies() {
            return Map.of("one split", Map.of("splits", 1), "many splits", Map.of("splits", 64));
        }
    }

    @Override
    protected SourceUnderTest source() {
        return new RowsSource();
    }
}
