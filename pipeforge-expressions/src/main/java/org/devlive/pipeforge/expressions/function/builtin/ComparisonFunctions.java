/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function.builtin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.devlive.pipeforge.expressions.function.Scalar;
import org.devlive.pipeforge.expressions.runtime.R;

/** Comparison and test functions. COALESCE, NULLIF, IFNULL and IF are part of the syntax. */
public final class ComparisonFunctions {

    private ComparisonFunctions() {}

    /** The largest argument; NULL when any argument is NULL. Arguments share their common type. */
    public static Object greatest(Object... values) {
        Object best = null;
        for (Object value : values) {
            if (value == null) {
                return null;
            }
            if (best == null || R.compare(value, best) > 0) {
                best = value;
            }
        }
        return best;
    }

    /** The smallest argument; NULL when any argument is NULL. */
    public static Object least(Object... values) {
        Object best = null;
        for (Object value : values) {
            if (value == null) {
                return null;
            }
            if (best == null || R.compare(value, best) < 0) {
                best = value;
            }
        }
        return best;
    }

    /** Whether the text is a number; FALSE for NULL. */
    @Scalar(name = "IS_NUMERIC", nullOnNull = false)
    public static boolean isNumeric(String value) {
        if (value == null) {
            return false;
        }
        try {
            new BigDecimal(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Whether the text is an ISO date (yyyy-MM-dd); FALSE for NULL. */
    @Scalar(name = "IS_DATE", nullOnNull = false)
    public static boolean isDate(String value) {
        if (value == null) {
            return false;
        }
        try {
            LocalDate.parse(value.trim());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
