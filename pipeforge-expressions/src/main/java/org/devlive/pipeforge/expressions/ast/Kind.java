/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.ast;

/** Node kinds of a typed expression tree. */
public enum Kind {
    /** A constant; {@code payload} is the value (null for NULL). */
    LITERAL,
    /** A column of the input row; {@code payload} is the position. */
    INPUT,
    /** A field of a ROW value; {@code payload} is the field position. */
    FIELD,
    /** {@code array[i]} (1-based) or {@code map[key]}; NULL when absent. */
    ITEM,
    /** A metadata pseudo-column; {@code payload} is its name ({@code $table}, ...). */
    META,
    /** {@code CAST}; {@code payload} is {@code true} for TRY_CAST. */
    CAST,
    PLUS,
    MINUS,
    TIMES,
    DIVIDE,
    MOD,
    NEGATE,
    CONCAT,
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    IS_DISTINCT,
    IS_NOT_DISTINCT,
    AND,
    OR,
    NOT,
    IS_NULL,
    IS_NOT_NULL,
    IS_TRUE,
    IS_FALSE,
    IS_NOT_TRUE,
    IS_NOT_FALSE,
    /** {@code value IN (items...)}: args are the value followed by the items. */
    IN,
    /** {@code value LIKE pattern [ESCAPE escape]}. */
    LIKE,
    /** {@code value RLIKE regex} (RE2 syntax, partial match). */
    RLIKE,
    /** {@code CASE WHEN c1 THEN r1 ... ELSE e END}: args are c1, r1, ..., e. */
    CASE,
    /** First non-null argument, evaluated lazily. */
    COALESCE,
    /** {@code ARRAY[...]}. */
    ARRAY,
    /** {@code MAP[k1, v1, ...]}. */
    MAP,
    /** A registered function; {@code payload} is the {@code FunctionDefinition}. */
    FUNCTION
}
