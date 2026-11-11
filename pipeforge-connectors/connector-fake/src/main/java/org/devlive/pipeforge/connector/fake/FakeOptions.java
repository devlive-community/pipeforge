/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import java.time.Duration;
import java.util.List;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.Options;

/** The options of the fake source. */
final class FakeOptions {

    static final List<String> DEFAULT_COLUMNS =
            List.of("id INT64 NOT NULL", "name STRING(32)", "amount DECIMAL(10, 2)", "created_at TIMESTAMP(3)");

    static final Option<String> TABLE = Options.key("table")
            .stringType()
            .defaultValue("fake.rows")
            .withGroup("schema")
            .withDescription("The table the rows belong to, as database.table.");
    static final Option<List<String>> TABLES = Options.key("tables")
            .listType()
            .noDefault()
            .withGroup("schema")
            .withDescription("Several tables with the same columns, instead of table: every table gets its own rows.");
    static final Option<List<String>> COLUMNS = Options.key("columns")
            .listType()
            .defaultValue(DEFAULT_COLUMNS)
            .withGroup("schema")
            .withDescription("The columns, each as 'name TYPE', e.g. 'amount DECIMAL(10, 2) NOT NULL'. Write the list"
                    + " as a YAML list: types may contain commas.");
    static final Option<List<String>> PRIMARY_KEY = Options.key("primary-key")
            .listType()
            .noDefault()
            .withGroup("schema")
            .withDescription("The primary key columns; integer key columns count 0, 1, 2, ... and string key columns"
                    + " are 'key-<n>'. Defaults to id with the default columns.");
    static final Option<Long> ROWS = Options.key("rows")
            .longType()
            .defaultValue(1000L)
            .withRange(0, null)
            .withGroup("data")
            .withDescription("Rows generated per table.");
    static final Option<List<String>> DATA = Options.key("data")
            .listType()
            .noDefault()
            .withGroup("data")
            .withDescription("Literal rows instead of generated ones, each a JSON array of the column values in"
                    + " column order. Decimals, dates, times and timestamps are written as strings, binary values"
                    + " in Base64, maps as objects and nested rows as arrays or objects.");
    static final Option<Long> SEED = Options.key("seed")
            .longType()
            .defaultValue(0L)
            .withGroup("data")
            .withDescription("Seed of the generated values: the same seed gives the same rows, at any parallelism.");
    static final Option<Double> NULL_RATIO = Options.key("null-ratio")
            .doubleType()
            .defaultValue(0.1)
            .withRange(0.0, 1.0)
            .withGroup("data")
            .withDescription("Share of NULL values in nullable columns that are not part of the primary key.");
    static final Option<Double> BAD_ROW_RATIO = Options.key("bad-row-ratio")
            .doubleType()
            .defaultValue(0.0)
            .withRange(0.0, 1.0)
            .withGroup("data")
            .withDescription("Share of rows with a value that breaks its column type: NULL in a NOT NULL column, a"
                    + " string longer than its length, or a decimal with too many digits.");
    static final Option<Integer> SPLITS = Options.key("splits")
            .intType()
            .defaultValue(4)
            .withRange(1, 10_000)
            .withGroup("reading")
            .withDescription("Splits per table that readers pull; use at least the parallelism.");
    static final Option<Double> ROWS_PER_SECOND = Options.key("rows-per-second")
            .doubleType()
            .defaultValue(0.0)
            .withRange(0.0, null)
            .withGroup("reading")
            .withDescription("Maximum rows per second of the whole source, shared by its readers; 0 for no limit.");
    static final Option<Boolean> UNBOUNDED = Options.key("unbounded")
            .booleanType()
            .defaultValue(false)
            .withGroup("reading")
            .withDescription("Generate rows forever, as a streaming source; rows is then ignored.");
    static final Option<String> EVENT_TIME_COLUMN = Options.key("event-time-column")
            .stringType()
            .noDefault()
            .withGroup("event time")
            .withDescription("A TIMESTAMP, TIMESTAMP_LTZ or INT64 (epoch milliseconds) column that receives the event"
                    + " time of each row; the rows are emitted with it.");
    static final Option<String> START_TIME = Options.key("start-time")
            .stringType()
            .defaultValue("2026-01-01T00:00:00Z")
            .withGroup("event time")
            .withDescription("The event time of the first row, as an ISO-8601 instant.");
    static final Option<Duration> EVENT_TIME_STEP = Options.key("event-time-step")
            .durationType()
            .defaultValue(Duration.ofSeconds(1))
            .withGroup("event time")
            .withDescription("How far the event time advances from one row to the next.");
    static final Option<Duration> MAX_OUT_OF_ORDER = Options.key("max-out-of-order")
            .durationType()
            .defaultValue(Duration.ZERO)
            .withGroup("event time")
            .withDescription(
                    "Rows arrive late by a random delay up to this duration, so event times are out of" + " order.");

    private FakeOptions() {}
}
