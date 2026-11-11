/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.option;

import org.devlive.pipeforge.api.annotation.Stable;

/** Value types of options. */
@Stable
public enum OptionType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    /** {@code java.time.Duration}, written as {@code 30s}, {@code 5m}, {@code 1h}, {@code 200ms} or ISO-8601. */
    DURATION,
    /** One of a fixed set of upper-case values. */
    ENUM,
    /** A list of strings, written as a YAML list or a comma-separated string. */
    LIST,
    /** A map of strings. */
    MAP,
    /**
     * A list of objects, each a mapping of keys to plain values (strings, numbers, booleans), e.g.
     * {@code [{ from: id, to: order_id }]}. Values are read as strings; empty values are left out.
     */
    OBJECT_LIST
}
