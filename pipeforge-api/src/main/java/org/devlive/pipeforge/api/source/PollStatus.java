/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import org.devlive.pipeforge.api.annotation.Stable;

/** Result of {@link SourceReader#pollNext}. */
@Stable
public enum PollStatus {
    /** More data can be read immediately. */
    MORE_AVAILABLE,
    /** Nothing to read right now; the engine backs off before polling again. */
    NOTHING_AVAILABLE,
    /** The reader has no splits left and will not get more. */
    END_OF_INPUT
}
