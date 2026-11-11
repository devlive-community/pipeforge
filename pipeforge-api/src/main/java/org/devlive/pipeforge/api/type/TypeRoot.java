/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.type;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.row.Row;

/** The logical type families and the Java class that represents their values. */
@Stable
public enum TypeRoot {
    BOOLEAN(Boolean.class),
    INT8(Byte.class),
    INT16(Short.class),
    INT32(Integer.class),
    INT64(Long.class),
    FLOAT32(Float.class),
    FLOAT64(Double.class),
    DECIMAL(BigDecimal.class),
    STRING(String.class),
    BYTES(byte[].class),
    DATE(LocalDate.class),
    TIME(LocalTime.class),
    TIMESTAMP(LocalDateTime.class),
    TIMESTAMP_LTZ(Instant.class),
    ARRAY(List.class),
    MAP(Map.class),
    ROW(Row.class),
    /** A string that is guaranteed to contain valid JSON. */
    JSON(String.class),
    /** The type of the NULL literal; has no values. */
    NULL(Void.class);

    private final Class<?> javaClass;

    TypeRoot(Class<?> javaClass) {
        this.javaClass = javaClass;
    }

    public Class<?> javaClass() {
        return javaClass;
    }

    public boolean isNumeric() {
        return switch (this) {
            case INT8, INT16, INT32, INT64, FLOAT32, FLOAT64, DECIMAL -> true;
            default -> false;
        };
    }

    public boolean isIntegral() {
        return switch (this) {
            case INT8, INT16, INT32, INT64 -> true;
            default -> false;
        };
    }

    public boolean isTemporal() {
        return switch (this) {
            case DATE, TIME, TIMESTAMP, TIMESTAMP_LTZ -> true;
            default -> false;
        };
    }
}
