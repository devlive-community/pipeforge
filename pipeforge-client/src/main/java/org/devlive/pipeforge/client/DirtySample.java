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
 * A bad row a run skipped.
 *
 * @param node where
 * @param table its table
 * @param code the error code
 * @param message why
 * @param row the row as text
 * @param at when
 */
public record DirtySample(String node, @Nullable String table, String code, String message, String row, Instant at) {}
