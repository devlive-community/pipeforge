/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

/** How a JDBC source splits a table. */
public enum JdbcSplitMode {
    /** Ranges of an integer column between its minimum and maximum, plus one for its NULLs. */
    RANGE,
    /** The whole table, or the query, in one split. */
    NONE
}
