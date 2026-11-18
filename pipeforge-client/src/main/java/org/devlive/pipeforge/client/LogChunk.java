/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

/**
 * A part of a task log.
 *
 * @param text the entries
 * @param offset where the next read continues
 * @param eof the log was read to its end and the task ended
 */
public record LogChunk(String text, long offset, boolean eof) {}
