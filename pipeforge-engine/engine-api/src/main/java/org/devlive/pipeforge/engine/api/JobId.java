/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.Objects;
import java.util.UUID;

/** Identifies one submitted job in an engine: a UUID string. */
public record JobId(String value) {

    public JobId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("A job id must not be blank");
        }
    }

    public static JobId random() {
        return new JobId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
