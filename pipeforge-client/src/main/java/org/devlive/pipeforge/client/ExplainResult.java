/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The plan of a job, as a document (see the explain documentation) and as text.
 *
 * @param plan the plan document
 * @param text the plan as text
 */
public record ExplainResult(JsonNode plan, String text) {}
