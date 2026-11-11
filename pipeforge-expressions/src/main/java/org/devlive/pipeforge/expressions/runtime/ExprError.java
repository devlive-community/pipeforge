/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.runtime;

/**
 * A run-time failure of an expression (division by zero, failed cast, overflow, invalid
 * argument). It carries no stack trace: it is part of normal data handling, not a defect.
 */
public final class ExprError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExprError(String reason) {
        super(reason, null, false, false);
    }
}
