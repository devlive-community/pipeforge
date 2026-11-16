/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import org.devlive.pipeforge.api.row.Row;
import org.jspecify.annotations.Nullable;

/**
 * Estimates the bytes a row takes on the heap: object headers, references and the content of its
 * values, nested rows, lists and maps included. The estimate is meant to compare rows with a limit
 * and to account buffers, not to be exact; strings count two bytes per character.
 */
final class RowSize {

    private static final int HEADER = 16;
    private static final int REFERENCE = 8;

    private RowSize() {}

    static long estimate(Row row) {
        long size = HEADER + HEADER + (long) REFERENCE * row.arity();
        for (int i = 0; i < row.arity(); i++) {
            size += value(row.get(i));
        }
        return size;
    }

    static long value(@Nullable Object value) {
        return switch (value) {
            case null -> 0;
            case String s -> 40 + 2L * s.length();
            case byte[] bytes -> HEADER + bytes.length;
            case Row nested -> estimate(nested);
            case BigDecimal decimal -> 48 + decimal.unscaledValue().bitLength() / 8;
            case BigInteger integer -> 40 + integer.bitLength() / 8;
            case Collection<?> list -> {
                long size = 24 + HEADER + (long) REFERENCE * list.size();
                for (Object element : list) {
                    size += value(element);
                }
                yield size;
            }
            case Map<?, ?> map -> {
                long size = 48 + 40L * map.size();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    size += value(entry.getKey()) + value(entry.getValue());
                }
                yield size;
            }
                // boxed numbers, booleans, dates and times
            default -> 24;
        };
    }
}
