/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network;

import java.time.Duration;

/**
 * The settings of the data exchange of a worker.
 *
 * @param memoryBytes the network memory of the worker ({@code worker.memory.network})
 * @param bufferSize the size of a network buffer ({@code network.buffer-size})
 * @param buffersPerChannel buffers reserved for each input channel ({@code network.buffers-per-channel})
 * @param floatingBuffersPerGate buffers each input shares among its channels
 *     ({@code network.floating-buffers-per-gate})
 * @param maxRowBytes the largest row ({@code task.max-row-bytes})
 * @param bufferTimeout after how long a buffer that is not full is sent ({@code network.buffer-timeout})
 * @param pingInterval how often an idle connection sends a ping; three missing intervals close it
 * @param partitionRequestTimeout how long a request waits for an output that is not there yet
 * @param connectTimeout how long connecting to another worker may take
 */
public record NetworkOptions(
        long memoryBytes,
        int bufferSize,
        int buffersPerChannel,
        int floatingBuffersPerGate,
        int maxRowBytes,
        Duration bufferTimeout,
        Duration pingInterval,
        Duration partitionRequestTimeout,
        Duration connectTimeout) {

    public static final long DEFAULT_MEMORY = 64L << 20;
    public static final int DEFAULT_BUFFER_SIZE = 32 << 10;
    public static final int DEFAULT_MAX_ROW_BYTES = 64 << 20;

    public NetworkOptions {
        if (bufferSize < 1024) {
            throw new IllegalArgumentException("network.buffer-size must be at least 1024 bytes: " + bufferSize);
        }
        if (memoryBytes < bufferSize) {
            throw new IllegalArgumentException("worker.memory.network must hold at least one buffer: " + memoryBytes);
        }
        if (buffersPerChannel < 1) {
            throw new IllegalArgumentException("network.buffers-per-channel must be at least 1: " + buffersPerChannel);
        }
        if (floatingBuffersPerGate < 0) {
            throw new IllegalArgumentException(
                    "network.floating-buffers-per-gate must not be negative: " + floatingBuffersPerGate);
        }
        if (maxRowBytes < bufferSize) {
            throw new IllegalArgumentException("task.max-row-bytes must not be smaller than a buffer: " + maxRowBytes);
        }
        for (Duration d : new Duration[] {bufferTimeout, pingInterval, partitionRequestTimeout, connectTimeout}) {
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("network timeouts must be positive: " + d);
            }
        }
    }

    /** 64 MB of 32 KB buffers, 2 per channel and 8 floating per input, rows up to 64 MB. */
    public static NetworkOptions defaults() {
        return new NetworkOptions(
                DEFAULT_MEMORY,
                DEFAULT_BUFFER_SIZE,
                2,
                8,
                DEFAULT_MAX_ROW_BYTES,
                Duration.ofMillis(100),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10));
    }

    /** The number of buffers the network memory holds. */
    public int totalBuffers() {
        return (int) Math.min(Integer.MAX_VALUE, memoryBytes / bufferSize);
    }

    /** The largest frame: a row of {@link #maxRowBytes} and 1 KB for the frame around it. */
    public int maxFrameBytes() {
        return (int) Math.min(Integer.MAX_VALUE, (long) maxRowBytes + 1024);
    }

    public NetworkOptions withMemory(long bytes, int size) {
        return new NetworkOptions(
                bytes,
                size,
                buffersPerChannel,
                floatingBuffersPerGate,
                Math.max(maxRowBytes, size),
                bufferTimeout,
                pingInterval,
                partitionRequestTimeout,
                connectTimeout);
    }

    public NetworkOptions withChannels(int perChannel, int floating) {
        return new NetworkOptions(
                memoryBytes,
                bufferSize,
                perChannel,
                floating,
                maxRowBytes,
                bufferTimeout,
                pingInterval,
                partitionRequestTimeout,
                connectTimeout);
    }

    public NetworkOptions withTimeouts(Duration buffer, Duration ping, Duration partitionRequest) {
        return new NetworkOptions(
                memoryBytes,
                bufferSize,
                buffersPerChannel,
                floatingBuffersPerGate,
                maxRowBytes,
                buffer,
                ping,
                partitionRequest,
                connectTimeout);
    }

    public NetworkOptions withMaxRowBytes(int bytes) {
        return new NetworkOptions(
                memoryBytes,
                bufferSize,
                buffersPerChannel,
                floatingBuffersPerGate,
                bytes,
                bufferTimeout,
                pingInterval,
                partitionRequestTimeout,
                connectTimeout);
    }
}
