/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

/**
 * The versions of the server.
 *
 * @param version PipeForge
 * @param connectorApi the connector API
 * @param java the Java runtime
 */
public record VersionInfo(String version, String connectorApi, String java) {}
