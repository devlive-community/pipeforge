/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A change of the state of a run.
 *
 * @param attempt the attempt
 * @param from the state before, none for the first
 * @param to the state after
 * @param at when
 * @param reason why
 */
public record Transition(
        @Nullable Integer attempt, @Nullable String from, String to, Instant at, @Nullable String reason) {}
