/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import org.devlive.pipeforge.api.annotation.Stable;

/** A part of the data that one reader reads independently, e.g. a key range or a partition. */
@Stable
public interface SourceSplit {

    /** Identifier, unique within the source. */
    String splitId();
}
