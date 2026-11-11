/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.serialization;

import java.io.IOException;
import org.devlive.pipeforge.api.annotation.Stable;

/**
 * Serializes splits, committables and state with a version, so that a newer plugin can read
 * what an older one wrote (checkpoints and savepoints survive upgrades).
 */
@Stable
public interface VersionedSerializer<T> {

    /** The version written by {@link #serialize}; increase it whenever the format changes. */
    int version();

    byte[] serialize(T value) throws IOException;

    /** Reads bytes written by any version up to {@link #version()}. */
    T deserialize(int version, byte[] bytes) throws IOException;
}
