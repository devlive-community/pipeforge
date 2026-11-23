/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api.checkpoint;

import java.util.Map;

/**
 * Opens checkpoint storage of one scheme, such as {@code s3}; found with {@link
 * java.util.ServiceLoader} under {@code META-INF/services}.
 */
public interface CheckpointStorageFactory {

    /** The scheme of the locations it opens. */
    String scheme();

    /**
     * Opens the storage at {@code location}.
     *
     * @param settings the {@code checkpoint.*} settings of the process, keys without the prefix,
     *     such as {@code s3.endpoint}
     */
    CheckpointStorage open(java.net.URI location, Map<String, String> settings);
}
