/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.testkit.SinkContract;
import org.devlive.pipeforge.testkit.SinkUnderTest;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The testkit contract with the JSON format: what the sink prints is read back and compared row by
 * row, so every logical type round-trips through the printed text.
 */
class ConsoleSinkContractTest extends SinkContract {

    private static CapturedOutput output;

    @BeforeAll
    static void capture() {
        output = new CapturedOutput();
    }

    @AfterAll
    static void release() {
        output.close();
    }

    @Override
    protected SinkUnderTest sink() {
        return new SinkUnderTest() {
            @Override
            public SinkFactory factory() {
                return new ConsoleSinkFactory();
            }

            @Override
            public Map<String, Object> options(TestTable table) {
                output.clear();
                return Map.of("format", "json");
            }

            @Override
            public Optional<List<Row>> readBack(TestTable table, Map<String, Object> options) throws Exception {
                return Optional.of(JsonRows.read(output.lines(), table.table()));
            }

            @Override
            public void cleanup(TestTable table, Map<String, Object> options) {
                output.clear();
            }
        };
    }
}
