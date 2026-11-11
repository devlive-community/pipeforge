/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.expression;

import java.util.Objects;
import org.devlive.pipeforge.api.annotation.Evolving;
import org.devlive.pipeforge.api.type.DataType;

/**
 * What planning knows about an expression.
 *
 * @param text the expression as written
 * @param type the logical type of its result
 * @param deterministic whether the same input always gives the same result ({@code NOW()} does not)
 */
@Evolving
public record ExpressionInfo(String text, DataType type, boolean deterministic) {

    public ExpressionInfo {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(type, "type");
    }
}
