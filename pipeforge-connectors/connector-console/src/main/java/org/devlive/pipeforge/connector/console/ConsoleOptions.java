/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.Options;

/** The options of the console sink. */
final class ConsoleOptions {

    /** How rows are printed. */
    enum Format {
        /** Aligned tables of up to batch-size rows, one per table and batch. */
        TABLE,
        /** One JSON object per row and line. */
        JSON
    }

    /** Where rows are printed. */
    enum Target {
        STDOUT,
        STDERR
    }

    static final Option<Format> FORMAT = Options.key("format")
            .enumType(Format.class)
            .defaultValue(Format.TABLE)
            .withGroup("output")
            .withDescription("TABLE prints aligned tables, JSON one JSON object per row and line.");
    static final Option<Target> TARGET = Options.key("target")
            .enumType(Target.class)
            .defaultValue(Target.STDOUT)
            .withGroup("output")
            .withDescription("The stream the rows are printed to: the standard output or the standard error.");
    static final Option<Long> LIMIT = Options.key("limit")
            .longType()
            .noDefault()
            .withRange(0, null)
            .withGroup("output")
            .withDescription("Rows each parallel instance prints at most; the rest are still consumed and counted,"
                    + " and the number of rows not printed is reported at the end. All rows when not set.");
    static final Option<Boolean> PRINT_KIND = Options.key("print-kind")
            .booleanType()
            .defaultValue(true)
            .withGroup("output")
            .withDescription("Prints the kind of each row (+I insert, -U and +U update, -D delete): as the op"
                    + " column in tables, as the kind field in JSON.");
    static final Option<Integer> BATCH_SIZE = Options.key("batch-size")
            .intType()
            .defaultValue(20)
            .withRange(1, 10_000)
            .withGroup("table format")
            .withDescription("Rows per printed table: a table is printed when it has this many rows, at every"
                    + " checkpoint and at the end of the input.");
    static final Option<Integer> MAX_WIDTH = Options.key("max-width")
            .intType()
            .defaultValue(40)
            .withRange(0, 100_000)
            .withGroup("table format")
            .withDescription("Longer values are cut to this many characters and end with …; 0 prints values in full."
                    + " JSON lines are never cut.");

    private ConsoleOptions() {}
}
