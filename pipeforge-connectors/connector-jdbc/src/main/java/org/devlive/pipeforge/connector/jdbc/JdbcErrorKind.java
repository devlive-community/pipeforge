/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

/** What a database error means for the job, as a dialect classifies it. */
public enum JdbcErrorKind {
    /** The connection could not be made or broke: worth reconnecting and retrying. */
    CONNECTION,
    /** A timeout, a deadlock or a serialization failure: worth retrying. */
    TRANSIENT,
    /** The login was refused. */
    AUTH,
    /** A table or column does not exist. */
    NOT_FOUND,
    /** The data of a row is wrong: a bad row for the dirty data policy. */
    DATA,
    /** Anything else: the job fails. */
    OTHER
}
