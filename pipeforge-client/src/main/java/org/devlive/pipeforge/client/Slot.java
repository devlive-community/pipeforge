/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import org.jspecify.annotations.Nullable;

/**
 * A slot of a worker.
 *
 * @param index its index
 * @param allocated whether a run holds it
 * @param job the job whose run holds it
 */
public record Slot(int index, boolean allocated, @Nullable String job) {}
