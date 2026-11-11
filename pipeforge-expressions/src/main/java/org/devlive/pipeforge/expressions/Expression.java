/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.expressions.ast.Node;

/**
 * An analyzed expression: its text and its typed tree.
 *
 * @param text the expression as written
 * @param root the typed tree
 */
public record Expression(String text, Node root) {

    /** The result type, known before the job runs. */
    public DataType type() {
        return root.type();
    }

    /** Whether the same input always gives the same result. */
    public boolean deterministic() {
        return root.deterministic();
    }
}
