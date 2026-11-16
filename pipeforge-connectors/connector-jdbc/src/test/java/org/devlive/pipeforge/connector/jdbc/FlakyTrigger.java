/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.api.Trigger;

/** An H2 trigger that fails the next inserts as a deadlock would, to test retries. */
public final class FlakyTrigger implements Trigger {

    /** How many more inserts fail. */
    static final AtomicInteger FAILURES = new AtomicInteger();

    @Override
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        if (FAILURES.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new SQLException("simulated deadlock", "40001");
        }
    }
}
