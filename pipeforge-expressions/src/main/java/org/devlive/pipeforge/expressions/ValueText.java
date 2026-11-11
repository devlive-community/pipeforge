/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.expressions.runtime.ExprError;
import org.devlive.pipeforge.expressions.runtime.R;
import org.jspecify.annotations.Nullable;

/**
 * Values written as text, for tools that take values from users or show results: the conversions
 * of {@code CAST(text AS type)} and {@code CAST(value AS STRING)}, except that booleans are shown
 * as {@code TRUE} / {@code FALSE}, decimals without an exponent and arrays as {@code [a, b]}.
 */
public final class ValueText {

    private ValueText() {}

    /**
     * Converts text to a value of {@code type}; {@code null} stays NULL.
     *
     * @throws IllegalArgumentException with the reason when the text is not a value of the type
     */
    public static @Nullable Object parse(@Nullable String text, DataType type, ZoneId zone) {
        try {
            return R.cast(text, DataTypes.STRING(), type, zone);
        } catch (ExprError e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** Writes a value of {@code type} as text; NULL is {@code null}. */
    public static @Nullable String render(@Nullable Object value, DataType type, ZoneId zone) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                items.add(String.valueOf(render(item, type.elementType(), zone)));
            }
            return "[" + String.join(", ", items) + "]";
        }
        if (type.root() == TypeRoot.MAP && value instanceof Map<?, ?> map) {
            Map<String, String> rendered = new LinkedHashMap<>();
            map.forEach((k, v) -> rendered.put(
                    String.valueOf(render(k, type.keyType(), zone)),
                    String.valueOf(render(v, type.valueType(), zone))));
            return rendered.toString();
        }
        return (String) R.cast(value, type, DataTypes.STRING(), zone);
    }
}
