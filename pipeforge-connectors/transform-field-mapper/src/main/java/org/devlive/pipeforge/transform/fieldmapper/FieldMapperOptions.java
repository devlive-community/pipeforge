/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.expression.OnError;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.Options;

/** The options of the field mapper. */
final class FieldMapperOptions {

    /** What happens to input columns that no mapping entry and no drop names. */
    enum Others {
        KEEP,
        DROP
    }

    /** What happens when a mapping entry or drop names a column the input table does not have. */
    enum Unmapped {
        FAIL,
        DROP
    }

    /** How the names of output columns taken from input columns are written. */
    enum NameCase {
        KEEP,
        LOWER,
        UPPER,
        SNAKE,
        CAMEL
    }

    /** The order of the output columns. */
    enum Order {
        SOURCE,
        MAPPING
    }

    static final Option<List<Map<String, String>>> MAPPING = Options.key("mapping")
            .objectListType()
            .noDefault()
            .withGroup("columns")
            .withDescription("Output columns, one entry each: from (input column), to (output name), expr (expression),"
                    + " value (constant), type, nullable, default and comment.");

    static final Option<List<String>> DROP =
            Options.key("drop").listType().noDefault().withGroup("columns").withDescription("Input columns to remove.");

    static final Option<Others> OTHERS = Options.key("others")
            .enumType(Others.class)
            .defaultValue(Others.KEEP)
            .withGroup("columns")
            .withDescription("Input columns that no entry and no drop names: keep them or drop them.");

    static final Option<Unmapped> UNMAPPED = Options.key("unmapped")
            .enumType(Unmapped.class)
            .defaultValue(Unmapped.FAIL)
            .withGroup("columns")
            .withDescription("When from or drop names a column the table does not have: fail, or drop the entry for"
                    + " that table.");

    static final Option<NameCase> NAME_CASE = Options.key("name-case")
            .enumType(NameCase.class)
            .defaultValue(NameCase.KEEP)
            .withGroup("names")
            .withDescription("How names taken from input columns are written: keep, lower, upper, snake or camel."
                    + " Names given in to are used as written.");

    static final Option<Order> ORDER = Options.key("order")
            .enumType(Order.class)
            .defaultValue(Order.SOURCE)
            .withGroup("names")
            .withDescription("Output column order: source (input order, new columns last) or mapping (the entries"
                    + " first, in their order, then the kept columns).");

    static final Option<OnError> ON_ERROR = Options.key("on-error")
            .enumType(OnError.class)
            .defaultValue(OnError.DIRTY)
            .withGroup("errors")
            .withDescription("When an expression fails on a row: dirty (a bad row), null (the value becomes NULL) or"
                    + " fail (the job fails).");

    private FieldMapperOptions() {}
}
