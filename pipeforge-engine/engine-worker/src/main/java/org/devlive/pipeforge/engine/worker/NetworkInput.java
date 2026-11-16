/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.network.NetworkEvent;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.devlive.pipeforge.engine.network.gate.InputGate;
import org.devlive.pipeforge.engine.network.row.RowCodec;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;

/**
 * Where the first node of a chain without a source reads: the input of the task, decoded into
 * rows and handed to the node in the order they arrive. The input ends when every upstream
 * subpartition has ended.
 */
final class NetworkInput {

    /** How long one poll waits for a buffer before the task looks at its mail again. */
    static final Duration POLL = Duration.ofMillis(20);

    private final InputGate gate;
    private final RowCodec codec;
    private final String node;

    /**
     * @param tables the tables the first node reads; the producer numbers them in the same order
     */
    NetworkInput(NetworkEnvironment network, TaskDeployment deployment, List<CatalogTable> tables) {
        if (deployment.inputs().size() != 1) {
            throw PipeForgeException.of(ErrorCode.TOPOLOGY_UNSUPPORTED)
                    .param("node", deployment.chain().head().id())
                    .param("reason", "it reads " + deployment.inputs().size() + " inputs; one is supported")
                    .build();
        }
        this.node = deployment.chain().head().id();
        TaskDeployment.Input input = deployment.inputs().get(0);
        List<InputGate.ChannelSpec> channels = new ArrayList<>();
        for (TaskDeployment.Channel channel : input.channels()) {
            ResultPartitionId partition = new ResultPartitionId(
                    channel.producer().runId(),
                    channel.producer().vertexId(),
                    channel.producer().subtask(),
                    channel.producer().attempt(),
                    channel.outputIndex());
            channels.add(
                    new InputGate.ChannelSpec(partition, channel.subpartition(), address(channel.producerAddress())));
        }
        this.codec = NetworkOutput.codec(tables);
        this.gate = network.createInputGate(deployment.id() + " input " + input.index(), channels);
    }

    static @org.jspecify.annotations.Nullable InetSocketAddress address(
            @org.jspecify.annotations.Nullable String hostPort) {
        if (hostPort == null) {
            return null;
        }
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("a data address without a port: " + hostPort);
        }
        return new InetSocketAddress(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)));
    }

    /** Hands the rows of the next buffer to {@code next}; waits up to {@link #POLL} for one. */
    PollStatus poll(ChainOutput next) throws InterruptedException {
        InputGate.Next arrived = gate.next(POLL);
        if (arrived == null) {
            return gate.isFinished() ? PollStatus.END_OF_INPUT : PollStatus.NOTHING_AVAILABLE;
        }
        Buffer buffer = arrived.buffer();
        try {
            if (buffer.isEvent()) {
                NetworkEvent event = NetworkEvent.fromBuffer(buffer);
                if (event instanceof NetworkEvent.SchemaChange) {
                    throw PipeForgeException.of(ErrorCode.TOPOLOGY_UNSUPPORTED)
                            .param("node", node)
                            .param(
                                    "reason",
                                    "it received a schema change from another task, which this version"
                                            + " cannot apply")
                            .scope(ErrorScope.node(node))
                            .build();
                }
                // the end of a bounded input: the end of every channel follows
                return PollStatus.MORE_AVAILABLE;
            }
            for (RowCodec.Decoded row : codec.decodeAll(buffer.content())) {
                next.row(row.row());
            }
        } finally {
            buffer.recycle();
        }
        return PollStatus.MORE_AVAILABLE;
    }

    /** Bytes of the network buffers the input holds now. */
    long memoryBytes() {
        return gate.bytesInUse();
    }

    long bytesReceived() {
        return gate.metrics().bytes();
    }

    InputGate.Metrics metrics() {
        return gate.metrics();
    }

    void close() {
        gate.close();
    }
}
