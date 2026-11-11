/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import java.io.Serializable;
import org.devlive.pipeforge.api.annotation.Stable;

/** A message from a reader to its enumerator, e.g. the high watermark of a finished snapshot chunk. */
@Stable
public interface ReaderEvent extends Serializable {}
