/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;

/** Sink capability: Failed batches leave no rows, so bad rows can be found by splitting the batch. */
@Evolving
public interface SupportsDirtyIsolation {}
