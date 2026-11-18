/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.util.List;

/**
 * Whether a job is valid, with its problems.
 *
 * @param valid no errors
 * @param errors the errors
 * @param warnings the warnings
 */
public record ValidationResult(boolean valid, List<ApiError> errors, List<ApiError> warnings) {

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
