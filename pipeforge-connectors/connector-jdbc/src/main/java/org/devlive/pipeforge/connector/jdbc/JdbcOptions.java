/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.time.Duration;
import java.util.List;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;

/**
 * The options JDBC sources and sinks share. A plugin builds its rule from them with {@link
 * #sourceRule} and {@link #sinkRule}, and adds its own.
 */
public final class JdbcOptions {

    /** The namespace of driver properties, passed to the driver as they are. */
    public static final String PROPERTIES = "jdbc.properties";

    public static final Option<String> URL =
            Options.key("url").stringType().noDefault().withDescription("The JDBC URL of the database");

    public static final Option<String> USERNAME = Options.key("username")
            .stringType()
            .noDefault()
            .withDescription("The user the connection logs in as")
            .withDeprecatedKeys("user");

    public static final Option<String> PASSWORD = Options.key("password")
            .stringType()
            .noDefault()
            .withDescription("The password of the user")
            .withSensitive(true);

    public static final Option<String> DRIVER = Options.key("driver")
            .stringType()
            .noDefault()
            .withDescription("The class of the JDBC driver; found from the URL when not set");

    public static final Option<String> TABLE = Options.key("table")
            .stringType()
            .noDefault()
            .withDescription("The table, e.g. shop.orders; the database or schema part follows the dialect");

    public static final Option<String> QUERY = Options.key("query")
            .stringType()
            .noDefault()
            .withDescription("A query whose result is read instead of a table, in one split");

    public static final Option<List<String>> TABLES_INCLUDE = Options.key("tables.include")
            .listType()
            .noDefault()
            .withDescription(
                    "Regular expressions of qualified table names, e.g. shop\\..*; every matching table is read");

    public static final Option<List<String>> TABLES_EXCLUDE = Options.key("tables.exclude")
            .listType()
            .defaultValue(List.of())
            .withDescription("Regular expressions of qualified table names that tables.include does not read");

    public static final Option<String> SPLIT_COLUMN = Options.key("split.column")
            .stringType()
            .noDefault()
            .withDescription("An integer column to split the table by; the primary key when it is one integer column");

    public static final Option<JdbcSplitMode> SPLIT_MODE = Options.key("split.mode")
            .enumType(JdbcSplitMode.class)
            .defaultValue(JdbcSplitMode.RANGE)
            .withDescription("range: ranges of the split column between its MIN and MAX; none: one split");

    public static final Option<Integer> SPLIT_COUNT = Options.key("split.count")
            .intType()
            .defaultValue(0)
            .withRange(0, 100_000)
            .withDescription("How many ranges to make; 0 for four per reader");

    public static final Option<Integer> FETCH_SIZE = Options.key("fetch-size")
            .intType()
            .defaultValue(1000)
            .withRange(1, null)
            .withDescription("Rows the driver fetches per round trip while it streams a split");

    public static final Option<Integer> BATCH_SIZE = Options.key("batch-size")
            .intType()
            .defaultValue(1000)
            .withRange(1, null)
            .withDescription("Rows written in one batch and one transaction");

    public static final Option<Integer> MAX_RETRIES = Options.key("max-retries")
            .intType()
            .defaultValue(3)
            .withRange(0, 100)
            .withDescription("How often a batch that failed for a transient reason is tried again");

    public static final Option<Duration> RETRY_BACKOFF = Options.key("retry-backoff")
            .durationType()
            .defaultValue(Duration.ofSeconds(1))
            .withDescription("The wait before the first retry; it doubles with every retry");

    private JdbcOptions() {}

    /** The rule of a JDBC source: connection, table or query, splits; plus {@code extra}. */
    public static OptionRule.Builder sourceRule(Option<?>... extra) {
        return OptionRule.builder()
                .required(URL)
                .exclusive(TABLE, QUERY, TABLES_INCLUDE)
                .optional(USERNAME, PASSWORD, DRIVER, TABLES_EXCLUDE, SPLIT_COLUMN, SPLIT_MODE, SPLIT_COUNT, FETCH_SIZE)
                .optional(extra)
                .nativeNamespaces(PROPERTIES);
    }

    /** The rule of a JDBC sink: connection, table, batches; plus {@code extra}. */
    public static OptionRule.Builder sinkRule(Option<?>... extra) {
        return OptionRule.builder()
                .required(URL, TABLE)
                .optional(USERNAME, PASSWORD, DRIVER, BATCH_SIZE, MAX_RETRIES, RETRY_BACKOFF)
                .optional(extra)
                .nativeNamespaces(PROPERTIES);
    }
}
