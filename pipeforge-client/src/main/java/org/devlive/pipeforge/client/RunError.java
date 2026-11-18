/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

/**
 * Why a run did not succeed.
 *
 * @param code the registered error code, e.g. PF-CLUS-0001
 * @param message the message
 */
public record RunError(String code, String message) {}
