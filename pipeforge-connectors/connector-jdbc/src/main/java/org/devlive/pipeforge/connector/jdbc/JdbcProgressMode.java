/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

/** Whether a JDBC sink keeps a progress table ({@code resume.progress-table}). */
public enum JdbcProgressMode {
    /** When the source runs in the same task and the table can be created; otherwise not, with a warning. */
    AUTO,
    /** Always; a table that cannot be created fails the task. */
    ON,
    /** Never: a restore writes the rows since the last checkpoint again. */
    OFF
}
