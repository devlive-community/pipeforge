/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.transform;

import org.devlive.pipeforge.api.annotation.Stable;

/** Receives the output of a transform. */
@Stable
@FunctionalInterface
public interface Collector<T> {

    void collect(T value);
}
