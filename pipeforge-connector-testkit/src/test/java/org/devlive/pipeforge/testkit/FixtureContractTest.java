/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.testkit.data.TestTable;

/**
 * A source with a rich option rule: its {@code options} group has cases for every feature, and its
 * {@code batch} group is skipped by the test {@code plugin.yaml}.
 */
class FixtureContractTest extends SourceContract {

    @Override
    protected SourceUnderTest source() {
        return new RowsSourceContractTest.RowsSource() {
            @Override
            public SourceFactory factory() {
                return new FixtureRowsFactory();
            }

            @Override
            public Map<String, Object> options(TestTable table) {
                Map<String, Object> options = new LinkedHashMap<>(super.options(table));
                options.put("table", "items");
                options.put("password", "s3cret-value");
                return options;
            }
        };
    }
}
