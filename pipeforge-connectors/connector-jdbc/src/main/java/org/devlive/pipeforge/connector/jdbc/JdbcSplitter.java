/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.row.TableId;
import org.jspecify.annotations.Nullable;

/**
 * Cuts the values of an integer column between its minimum and maximum into ranges of equal width.
 * The first range has no lower bound and the last none above, so rows written after the bounds
 * were probed are read too; a nullable column gets one more split for its NULLs.
 */
public final class JdbcSplitter {

    private JdbcSplitter() {}

    /**
     * The splits.
     *
     * @param table the table
     * @param column the split column
     * @param min the smallest value, or {@code null} when the column has none (an empty table)
     * @param max the largest value
     * @param count how many ranges at most
     * @param nullable whether the column may be NULL
     */
    public static List<JdbcSplit> ranges(
            TableId table, String column, @Nullable Long min, @Nullable Long max, int count, boolean nullable) {
        String prefix = table.toQualifiedName() + "/";
        List<JdbcSplit> splits = new ArrayList<>();
        if (min == null || max == null) {
            // nothing to cut: one split reads whatever is there
            splits.add(new JdbcSplit(prefix + "range-0", table, JdbcSplit.Kind.RANGE, column, null, null));
        } else {
            BigInteger span =
                    BigInteger.valueOf(max).subtract(BigInteger.valueOf(min)).add(BigInteger.ONE);
            BigInteger parts = BigInteger.valueOf(Math.max(1, count)).min(span);
            BigInteger width = span.add(parts).subtract(BigInteger.ONE).divide(parts);
            BigInteger lower = BigInteger.valueOf(min);
            int index = 0;
            while (lower.compareTo(BigInteger.valueOf(max)) <= 0) {
                BigInteger upper = lower.add(width);
                boolean first = index == 0;
                boolean last = upper.compareTo(BigInteger.valueOf(max)) > 0;
                splits.add(new JdbcSplit(
                        prefix + "range-" + index,
                        table,
                        JdbcSplit.Kind.RANGE,
                        column,
                        first ? null : lower.longValueExact(),
                        last ? null : upper.longValueExact()));
                lower = upper;
                index++;
            }
        }
        if (nullable) {
            splits.add(new JdbcSplit(prefix + "nulls", table, JdbcSplit.Kind.NULLS, column, null, null));
        }
        return splits;
    }
}
