/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import org.devlive.pipeforge.api.annotation.Stable;

/** Whether a source ends ({@code BOUNDED}, batch) or runs forever ({@code UNBOUNDED}, streaming). */
@Stable
public enum Boundedness {
    BOUNDED,
    UNBOUNDED
}
