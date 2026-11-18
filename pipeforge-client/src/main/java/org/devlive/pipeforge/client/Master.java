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
 * The master of a cluster.
 *
 * @param address its RPC address
 * @param version its version
 * @param protocol its protocol version
 * @param startedAt when it started
 */
public record Master(String address, String version, String protocol, @Nullable Instant startedAt) {}
