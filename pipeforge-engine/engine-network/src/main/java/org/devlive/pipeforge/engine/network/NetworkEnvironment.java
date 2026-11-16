/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.engine.network.buffer.NetworkBufferPool;
import org.devlive.pipeforge.engine.network.gate.InputGate;
import org.devlive.pipeforge.engine.network.netty.NettyTransport;
import org.devlive.pipeforge.engine.network.partition.ResultPartition;
import org.devlive.pipeforge.engine.network.partition.ResultPartitionManager;
import org.devlive.pipeforge.engine.network.row.RowCodec;
import org.jspecify.annotations.Nullable;

/**
 * The data exchange of a worker: its network memory, the outputs of its tasks, the server other
 * workers read them from and the connections it reads through. Tasks on the same worker exchange
 * buffers without the network.
 */
public final class NetworkEnvironment implements AutoCloseable {

    private final NetworkOptions options;
    private final String workerId;
    private final NetworkBufferPool pool;
    private final ResultPartitionManager partitions;
    private final NettyTransport transport;
    private @Nullable InetSocketAddress address;
    private @Nullable ScheduledFuture<?> flusher;

    /** @param clusterToken the token both ends of a connection must have */
    public NetworkEnvironment(String workerId, String clusterToken, NetworkOptions options) {
        this.options = options;
        this.workerId = workerId;
        this.pool = new NetworkBufferPool(options.totalBuffers(), options.bufferSize());
        this.partitions = new ResultPartitionManager(pool, options, workerId);
        this.transport = new NettyTransport(options, workerId, clusterToken, 2);
    }

    /**
     * Listens on {@code host:port} ({@code worker.data.port}; 0 takes a free port) and starts
     * sending buffers that are not full every buffer timeout.
     *
     * @return the address other workers connect to
     */
    public synchronized InetSocketAddress start(String host, int port) {
        if (address != null) {
            return address;
        }
        address = transport.bind(host, port, partitions);
        long every = options.bufferTimeout().toMillis();
        flusher = transport.timer().scheduleAtFixedRate(partitions::flushAll, every, every, TimeUnit.MILLISECONDS);
        return address;
    }

    public synchronized @Nullable InetSocketAddress address() {
        return address;
    }

    public String workerId() {
        return workerId;
    }

    public NetworkOptions options() {
        return options;
    }

    public NetworkBufferPool pool() {
        return pool;
    }

    public ResultPartitionManager partitions() {
        return partitions;
    }

    public NettyTransport transport() {
        return transport;
    }

    /** Creates the output of a task, with one subpartition per consumer subtask. */
    public ResultPartition createPartition(ResultPartitionId id, int subpartitions, RowCodec codec) {
        return partitions.create(id, subpartitions, codec);
    }

    /**
     * Creates an input of a task and asks its producers for their data. A channel whose producer
     * is this worker (or {@code null}) reads without the network.
     */
    public InputGate createInputGate(String owner, List<InputGate.ChannelSpec> channels) {
        InetSocketAddress self = address();
        List<InputGate.ChannelSpec> specs = new ArrayList<>(channels.size());
        for (InputGate.ChannelSpec spec : channels) {
            boolean local = spec.producer() == null || spec.producer().equals(self);
            specs.add(local ? new InputGate.ChannelSpec(spec.partition(), spec.subpartition(), null) : spec);
        }
        InputGate gate = new InputGate(owner, specs, options, pool, partitions, transport, transport.timer());
        gate.start();
        return gate;
    }

    @Override
    public void close() {
        ScheduledFuture<?> current;
        synchronized (this) {
            current = flusher;
            flusher = null;
        }
        if (current != null) {
            current.cancel(false);
        }
        transport.close();
        partitions.releaseAll();
    }
}
