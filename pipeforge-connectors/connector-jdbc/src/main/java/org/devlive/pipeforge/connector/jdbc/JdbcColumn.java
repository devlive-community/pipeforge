/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

/**
 * A column as JDBC metadata describes it.
 *
 * @param name the column name
 * @param sqlType the type in {@link java.sql.Types}
 * @param typeName the database's name of the type, e.g. {@code VARCHAR} or {@code TINYINT UNSIGNED}
 * @param size the precision or the length; 0 when not known
 * @param scale the scale, or the fractional digits of a time; 0 when not known
 * @param nullable whether the column allows NULL
 */
public record JdbcColumn(String name, int sqlType, String typeName, int size, int scale, boolean nullable) {}
