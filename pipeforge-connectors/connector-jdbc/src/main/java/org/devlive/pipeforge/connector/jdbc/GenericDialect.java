/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

/** The ANSI dialect, for databases without a dialect of their own (the generic JDBC plugin). */
public final class GenericDialect extends AbstractJdbcDialect {

    @Override
    public String name() {
        return "jdbc";
    }
}
