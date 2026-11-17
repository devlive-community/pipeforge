/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.Map;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.data.TestTable;

/**
 * A source whose system holds only floats of a narrow range passes the contract: the cases leave
 * out the rows with values it cannot hold, and the source never sees them.
 */
class NarrowSourceContractTest extends SourceContract {

    static boolean narrow(DataType type, Object value) {
        return type.root() != TypeRoot.FLOAT32 || Math.abs((Float) value) <= 1e30f;
    }

    @Override
    protected SourceUnderTest source() {
        return new RowsSourceContractTest.RowsSource() {
            @Override
            public Map<String, Object> options(TestTable table) {
                for (Row row : table.rows()) {
                    for (int i = 0; i < row.arity(); i++) {
                        Object value = row.get(i);
                        if (value != null
                                && !narrow(table.table().columns().get(i).type(), value)) {
                            throw new IllegalArgumentException("the system cannot hold " + value);
                        }
                    }
                }
                return super.options(table);
            }

            @Override
            public boolean holds(DataType type, Object value) {
                return narrow(type, value);
            }
        };
    }
}
